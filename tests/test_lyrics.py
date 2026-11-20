"""歌词核心逻辑测试（全部不联网）。

覆盖：LRC 解析与编码回退、静态歌词、演示歌词、标题清洗与候选生成、
LRCLIB 打分、来源阶梯、缓存与偏好、隐私（LRCLIB 请求不带 Cookie）。
"""

from __future__ import annotations

import asyncio
import json
import time

import httpx
import pytest

from bilimuse import config, lyrics
from bilimuse.api import BiliClient
from bilimuse.auth import Session

BV = "BV1xx411c7mD"

MANUAL_TRACK = {
    "id": 11,
    "lan": "zh-CN",
    "label": "中文（人工）",
    "url": "https://aisubtitle.hdslb.com/manual.json",
}
AI_TRACK = {
    "id": 22,
    "lan": "ai-zh",
    "label": "中文（AI）",
    "url": "https://aisubtitle.hdslb.com/ai.json",
}
AI_EN_TRACK = {
    "id": 33,
    "lan": "ai-en",
    "label": "English（AI）",
    "url": "https://aisubtitle.hdslb.com/ai-en.json",
}


def seg(frm: float, to: float, text: str) -> dict:
    return {"from": frm, "to": to, "content": text}


def record(**over) -> dict:
    """一条 LRCLIB 记录。"""
    data = {
        "id": 31664182,
        "trackName": "夜航星",
        "artistName": "不才",
        "albumName": "",
        "duration": 304,
        "instrumental": False,
        "syncedLyrics": "[00:01.00]夜航星",
        "plainLyrics": "夜航星",
    }
    data.update(over)
    return data


def planned_queries(title: str, upper: str = "") -> list[dict]:
    """按当前规则，一次解析会对 LRCLIB 发起的查询计划。"""
    return lyrics.query_plan(
        lyrics.title_candidates(title, upper), lyrics.clean_title(title)
    )


def calibration_record(*, shift: float = 0.0, count: int = 16, record_id: int = 42) -> dict:
    """一首时间规整、文本互不相同的 LRCLIB 同步歌词。"""
    lines = []
    for index in range(count):
        second = int(40 + index * 5 + shift)
        lines.append(f"[{second // 60:02d}:{second % 60:02d}.00]第{index}句歌词内容各不相同")
    return record(id=record_id, syncedLyrics="\n".join(lines))


def calibration_body(shift: float = 0.0, count: int = 16) -> dict:
    """与 calibration_record 对应、整体偏移 shift 秒的 AI 字幕。"""
    return {
        "body": [
            seg(
                40 + index * 5 + shift,
                41 + index * 5 + shift,
                f"♪第{index}句歌词内容各不相同♪",
            )
            for index in range(count)
        ]
    }


def manual_body() -> dict:
    return {"body": [seg(0, 1, "第一句"), seg(2, 3, "第二句")]}


def ai_body() -> dict:
    """四组合并后成四行的 AI 字幕。"""
    items = []
    base = 0.0
    for _ in range(4):
        items += [seg(base, base + 1, "我"), seg(base + 1.1, base + 2, "们")]
        base += 10
    return {"body": items}


class FakeBili:
    """假 B 站客户端：记录调用，按需返回字幕轨道与字幕体。"""

    def __init__(self, *, tracks=None, bodies=None, logged_in=True, duration=304):
        self.session = Session(
            cookies={"SESSDATA": "fake"} if logged_in else {},
            mid=1 if logged_in else 0,
        )
        self._tracks = list(tracks or [])
        self._bodies = dict(bodies or {})
        self.duration = duration
        self.calls: list[tuple] = []
        self.error: Exception | None = None

    async def page_info(self, bvid):
        self.calls.append(("page_info", bvid))
        if self.error:
            raise self.error
        return {"cid": 42, "duration": self.duration}

    async def subtitle_tracks(self, bvid, cid):
        self.calls.append(("subtitle_tracks", bvid, cid))
        if self.error:
            raise self.error
        return [dict(t) for t in self._tracks]

    async def subtitle_body(self, url):
        self.calls.append(("subtitle_body", url))
        if self.error:
            raise self.error
        return self._bodies[url]


@pytest.fixture()
def service(tmp_path, monkeypatch):
    monkeypatch.setattr(config.settings, "lyrics_online", True)
    monkeypatch.setattr(config.settings, "lyrics_bili", True)
    return lyrics.LyricsService(
        bili=None,
        http=None,
        lyrics_dir=tmp_path / "lyrics",
        cache_dir=tmp_path / "cache",
    )


def forbid_network(service, monkeypatch) -> None:
    """把解析器里所有真正发起请求的入口换成断言失败。"""

    async def boom(*_args, **_kwargs):
        raise AssertionError("测试不应发起网络请求")

    for name in (
        "_fetch_search",
        "_fetch_record",
        "_fetch_bili_tracks",
        "_fetch_bili_subtitle",
    ):
        monkeypatch.setattr(service, name, boom)


# ---------------------------------------------------------------- LRC 解析


def test_decode_lrc_handles_bom_and_crlf():
    raw = "\ufeff[00:01.00]第一行\r\n[00:02.50]第二行\r\n".encode("utf-8")
    text = lyrics.decode_lrc(raw)
    assert not text.startswith("\ufeff")
    lines, synced = lyrics.parse_lrc_text(text)
    assert synced is True
    assert [(line.t, line.text) for line in lines] == [
        (1.0, "第一行"),
        (2.5, "第二行"),
    ]


def test_decode_lrc_falls_back_to_gbk():
    raw = "[00:01.00]夜航星".encode("gbk")
    assert "夜航星" in lyrics.decode_lrc(raw)


def test_decode_lrc_replaces_undecodable_bytes():
    text = lyrics.decode_lrc(b"\xff\xfe\x00A")
    assert isinstance(text, str)
    assert "\ufffd" in text


def test_parse_multi_timestamp_expands_lines():
    lines, synced = lyrics.parse_lrc_text("[00:12.00][01:30.00]副歌")
    assert synced is True
    assert [(line.t, line.text) for line in lines] == [(12.0, "副歌"), (90.0, "副歌")]


def test_parse_offset_tag_applies_to_all_lines():
    lines, _ = lyrics.parse_lrc_text("[offset:-500]\n[00:10.00]负\n[00:12.00]也负")
    assert [line.t for line in lines] == [9.5, 11.5]

    lines, _ = lyrics.parse_lrc_text("[offset:+250]\n[00:10.00]正")
    assert [line.t for line in lines] == [10.25]


def test_parse_sorts_dedupes_and_keeps_non_negative_times():
    text = (
        "[00:05.00]b\n[00:01.00]a\n[00:05.00]b\n"
        "[00:03.00]c\n[00:01.00]d\n[offset:-2000]\n[00:00.50]早\n"
    )
    lines, synced = lyrics.parse_lrc_text(text)
    assert synced is True
    # offset -2s 叠加到所有行：0.5-2、1-2、3-2 都夹到 0，稳定排序保持原顺序
    assert [line.text for line in lines] == ["a", "d", "早", "c", "b"]
    assert [line.t for line in lines] == sorted(line.t for line in lines)
    assert min(line.t for line in lines) >= 0.0


def test_parse_trims_blank_lines_and_caps_runs():
    text = "\n".join(
        [
            "[00:01.00]",
            "[00:02.00]",
            "[00:03.00]",
            "[00:04.00]词",
            "[00:05.00]",
            "[00:06.00]",
            "[00:07.00]",
            "[00:08.00]尾",
            "[00:09.00]",
            "[00:10.00]",
            "[00:11.00]",
        ]
    )
    lines, synced = lyrics.parse_lrc_text(text)
    assert synced is True
    # 首尾空行裁掉，中间连续空行最多留 2 行
    assert [line.text for line in lines] == ["词", "", "", "尾"]
    assert [line.t for line in lines] == [4.0, 5.0, 6.0, 8.0]


def test_parse_static_lyrics_without_time_tags():
    text = "第一句\n\n第二句\n[ti:夜航星]\n[ar:不才]\n# 注释\n"
    lines, synced = lyrics.parse_lrc_text(text)
    assert synced is False
    # 静态歌词同样保留空行（参与滚动），首尾裁掉、连续最多 2 个
    assert [line.text for line in lines] == ["第一句", "", "第二句"]
    assert [line.t for line in lines] == [0.0, 1.0, 2.0]


def test_parse_static_lyrics_trims_and_caps_blank_runs():
    text = "\n\n第一句\n\n\n\n第二句\n\n\n"
    lines, synced = lyrics.parse_lrc_text(text)
    assert synced is False
    assert [line.text for line in lines] == ["第一句", "", "", "第二句"]
    assert [line.t for line in lines] == [0.0, 1.0, 2.0, 3.0]


def test_parse_ignores_metadata_lines():
    lines, synced = lyrics.parse_lrc_text(
        "[ti:夜航星]\n[ar:不才]\n[al:]\n[by:]\n[length:05:04]\n[00:01.00]唯一一句"
    )
    assert synced is True
    assert [(line.t, line.text) for line in lines] == [(1.0, "唯一一句")]


def test_parse_bytes_rejects_oversized_file():
    raw = b"x" * (lyrics.MAX_LRC_BYTES + 1)
    with pytest.raises(ValueError):
        lyrics.parse_lrc_bytes(raw)


def test_parse_file_truncates_to_line_limit(tmp_path):
    path = tmp_path / "long.lrc"
    path.write_text(
        "\n".join(f"[{i // 60:02d}:{i % 60:02d}.00]L{i}" for i in range(2001)),
        encoding="utf-8",
    )
    lines, synced = lyrics.parse_lrc_file(path)
    assert synced is True
    assert len(lines) == lyrics.MAX_LRC_LINES == 2000


def test_lyrics_dir_lives_under_config():
    assert config.LYRICS_DIR == config.CONFIG_DIR / "lyrics"


def test_settings_lyrics_flags_persist(tmp_path, monkeypatch):
    from bilimuse import config as config_module

    monkeypatch.setattr(config_module, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(config_module, "SETTINGS_FILE", tmp_path / "settings.json")

    settings = config_module.Settings()
    assert settings.public()["lyrics_online"] is True
    assert settings.public()["lyrics_bili"] is True

    settings.lyrics_online = False
    settings.lyrics_bili = False
    assert settings.save() is True

    restored = config_module.Settings()
    restored.load()
    assert restored.lyrics_online is False
    assert restored.lyrics_bili is False

    # 损坏 / 类型不对的值不能覆盖默认值
    (tmp_path / "settings.json").write_text(
        json.dumps({"lyrics_online": "yes", "lyrics_bili": 0}), encoding="utf-8"
    )
    fallback = config_module.Settings()
    fallback.load()
    assert fallback.lyrics_online is True
    assert fallback.lyrics_bili is True


def test_is_valid_bvid():
    assert lyrics.is_valid_bvid("DEMO0001") is True
    assert lyrics.is_valid_bvid("BV1xx411c7mD") is True
    assert lyrics.is_valid_bvid("") is False
    assert lyrics.is_valid_bvid("a" * 25) is False
    assert lyrics.is_valid_bvid("../etc/passwd") is False
    assert lyrics.is_valid_bvid("bad bvid!") is False


# ---------------------------------------------------------------- 演示歌词


def test_demo_lines_are_fixed():
    lines = lyrics.demo_lines()
    assert len(lines) == 8
    assert [line.t for line in lines] == [2.0, 7.0, 12.0, 17.0, 22.0, 27.0, 32.0, 37.0]
    assert [line.text for line in lines] == [
        "这是 45 秒的合成器演示音轨",
        "由本地 BTR 引擎分块并发加载",
        "右下角可以看到节点与实时速度",
        "关闭多线程，缓冲会明显变慢",
        "歌词来自本机演示数据",
        "不访问网络，也不属于任何歌曲",
        "打开设置可以切换三套界面风格",
        "按 L 可以随时收起歌词",
    ]


def test_demo_bvids():
    assert lyrics.is_demo_bvid("DEMO0001") is True
    assert lyrics.is_demo_bvid("DEMO0002") is True
    assert lyrics.is_demo_bvid("DEMO0003") is True
    assert lyrics.is_demo_bvid("DEMO0004") is False


async def test_demo_resolve_is_offline_and_labelled(service, monkeypatch):
    forbid_network(service, monkeypatch)
    monkeypatch.setattr(config.settings, "lyrics_online", False)
    monkeypatch.setattr(config.settings, "lyrics_bili", False)
    result = await service.resolve("DEMO0001")
    assert result["status"] == "ok"
    assert result["source"] == "demo"
    assert result["source_label"] == "演示歌词"
    assert result["synced"] is True
    assert len(result["lines"]) == 8
    # 演示歌词不落盘
    assert not list(service.cache_dir.glob("*")) if service.cache_dir.exists() else True


async def test_demo_local_lrc_wins_over_demo_lyrics(service, monkeypatch):
    forbid_network(service, monkeypatch)
    service.lyrics_dir.mkdir(parents=True)
    (service.lyrics_dir / "DEMO0001.lrc").write_text("[00:01.00]我自己写的", encoding="utf-8")
    result = await service.resolve("DEMO0001")
    assert result["source"] == "local"
    assert result["lines"] == [{"t": 1.0, "text": "我自己写的"}]
    sources = await service.sources("DEMO0001")
    assert {item["source"]: item["available"] for item in sources}["local"] is True


# ---------------------------------------------------------------- 清洗与候选


def test_clean_title_strips_noise_tags():
    assert lyrics.clean_title("【4K】夜航星 - 不才") == "夜航星 - 不才"
    assert lyrics.clean_title("孤勇者（Live版）") == "孤勇者"
    assert lyrics.clean_title("《起风了》买辣椒也用券") == "起风了 买辣椒也用券"
    # 「」是歌名引号，不是噪声标签：内容要保留
    assert lyrics.clean_title("「残酷な天使のテーゼ」高橋洋子") == "残酷な天使のテーゼ 高橋洋子"
    # feat. 子句不属于曲名
    assert lyrics.clean_title("孤勇者 feat. 陈奕迅") == "孤勇者"


def test_candidates_split_by_dash():
    assert lyrics.title_candidates("【4K】夜航星 - 不才", "UP主") == [
        ("夜航星", "不才"),
        ("不才", "夜航星"),
    ]
    assert lyrics.title_candidates("夜航星 — 不才", "UP主") == [
        ("夜航星", "不才"),
        ("不才", "夜航星"),
    ]


def test_candidates_reverse_order_for_artist_first_titles():
    # 「歌手 - 歌名」也是常见写法：反向候选必须进入列表
    assert lyrics.title_candidates("不才 - 夜航星", "UP主") == [
        ("不才", "夜航星"),
        ("夜航星", "不才"),
    ]
    # 去重并限制最多 3 个
    candidates = lyrics.title_candidates("《夜航星》不才 - 夜航星", "UP主")
    assert len(candidates) <= lyrics.MAX_CANDIDATES
    assert len(candidates) == len({(t.casefold(), a.casefold()) for t, a in candidates})


def test_candidates_use_book_title_and_outside_artist():
    assert lyrics.title_candidates("《起风了》买辣椒也用券", "UP主") == [
        ("起风了", "买辣椒也用券")
    ]


def test_candidates_fall_back_to_whole_title():
    assert lyrics.title_candidates("夜航星", "不才") == [("夜航星", "")]
    assert lyrics.title_candidates("孤勇者（Live版）", "陈奕迅") == [("孤勇者", "")]
    assert lyrics.title_candidates("", "UP主") == []


def test_candidates_keep_cover_song_name():
    # 「翻唱」去掉的是标签，歌名/歌手照常保留
    assert lyrics.title_candidates("起风了 翻唱 - 买辣椒也用券", "UP主") == [
        ("起风了", "买辣椒也用券"),
        ("买辣椒也用券", "起风了"),
    ]


def test_artist_score_matches_feat_components():
    # 记录歌手可能是「A feat. B」，候选只解析出 A
    assert lyrics.artist_score("陈奕迅", "陈奕迅 feat. G.E.M.邓紫棋") == 0.9
    assert lyrics.artist_score("陈奕迅", "G.E.M.邓紫棋 & 陈奕迅") == 0.9
    assert lyrics.artist_score("陈奕迅", "周杰伦") == 0.0


def test_feat_regex_does_not_match_inside_english_words():
    assert lyrics.clean_title("Soft Rain") == "soft rain"
    assert lyrics.clean_title("Gift of the Magi") == "gift of the magi"
    assert lyrics.clean_title("Daft Punk - Get Lucky") == "daft punk - get lucky"


def test_artist_split_does_not_split_ft_inside_english_words():
    # "Daft Punk" 不能被拆成 "Da" / "Punk"（否则会产生 0.9 的假歌手匹配）
    assert lyrics.artist_score("Da", "Daft Punk") < 0.9
    assert lyrics.artist_score("So", "Soft Cell") < 0.9


# ---------------------------------------------------------------- 打分


def test_score_exact_match_is_high():
    score = lyrics.score_record(record(), "夜航星", "不才", "UP主", 304)
    assert score == pytest.approx(0.98, abs=1e-6)


def test_score_contains_match():
    # 歌名包含得 0.8，歌手相等得 0.9
    score = lyrics.score_record(record(trackName="夜航星 (Live)"), "夜航星", "不才", "", 304)
    assert score == pytest.approx(0.86, abs=1e-6)


def test_score_artist_falls_back_to_uploader():
    rec = record(artistName="某某UP主")
    score = lyrics.score_record(rec, "夜航星", "", "某某UP主", 304)
    assert score == pytest.approx(0.98, abs=1e-6)


def test_duration_score_penalty():
    assert lyrics.duration_score(304, 304) == 1.0
    assert lyrics.duration_score(306, 304) == 1.0
    assert lyrics.duration_score(336, 304) == pytest.approx(1 - 30 / 60, abs=1e-6)
    assert lyrics.duration_score(1000, 304) == 0.0


def test_pick_match_rejects_instrumental():
    matched = lyrics.pick_match(
        [record(instrumental=True)], [("夜航星", "不才")], "UP主", 304
    )
    assert matched is None


def test_pick_match_requires_track_score():
    # track 二元组重合 0.5 → 总分 0.68 ≥ 0.62 但 track < 0.7，仍要拒绝
    matched = lyrics.pick_match(
        [record(trackName="夜航星辰", artistName="不才", duration=304)],
        [("夜航星空", "不才")],
        "",
        304,
    )
    assert matched is None


def test_pick_match_requires_total_score():
    # track 0.8 → 0.48，歌手 0，时长 0 → 0.48 < 0.62
    matched = lyrics.pick_match(
        [record(trackName="夜航星组曲", artistName="未知", duration=900)],
        [("夜航星", "不才")],
        "UP主",
        304,
    )
    assert matched is None


def test_pick_match_prefers_closer_duration():
    records = [
        record(id=1, duration=340),
        record(id=2, duration=304),
    ]
    matched = lyrics.pick_match(records, [("夜航星", "不才")], "", 304)
    assert matched is not None
    assert matched[0]["id"] == 2
    assert matched[1] == pytest.approx(0.98, abs=1e-6)


def test_pick_match_delegates_to_score_record(monkeypatch):
    calls: list[tuple[str, str]] = []

    def fake_score(record, track, artist="", upper="", video_duration=0):
        calls.append((track, artist))
        return 0.7

    monkeypatch.setattr(lyrics, "score_record", fake_score)
    matched = lyrics.pick_match([record()], [("夜航星", "不才")], "", 304)
    assert calls == [("夜航星", "不才")]
    assert matched is not None
    assert matched[1] == pytest.approx(0.7)


def test_pick_match_score_matches_score_record():
    rec = record(trackName="夜航星 (Live)", artistName="不才", duration=340)
    matched = lyrics.pick_match([rec], [("夜航星", "不才")], "", 304)
    assert matched is not None
    assert matched[1] == pytest.approx(
        lyrics.score_record(rec, "夜航星", "不才", "", 304)
    )


def test_rank_matches_skips_records_without_lyrics():
    ranked = lyrics.rank_matches(
        [
            record(id=1, syncedLyrics=None, plainLyrics=None),
            record(id=2),
        ],
        [("夜航星", "不才")],
        "",
        304,
    )
    assert [item[0]["id"] for item in ranked] == [2]


def test_rank_matches_rejects_artist_conflict():
    # 曲名完全一致、时长也一致，但歌手完全不符：不能因为总分 0.8 就接受
    ranked = lyrics.rank_matches(
        [record(id=1, artistName="周杰伦")],
        [("夜航星", "不才")],
        "",
        304,
    )
    assert ranked == []


def test_rank_matches_uploader_fallback_is_not_gated():
    ranked = lyrics.rank_matches(
        [record(id=1, artistName="某某UP主")],
        [("夜航星", "")],
        "某某UP主",
        304,
    )
    assert [item[0]["id"] for item in ranked] == [1]


def test_rank_matches_rejects_duration_far_apart():
    assert lyrics.rank_matches(
        [record(id=1, duration=304 + 46)], [("夜航星", "不才")], "", 304
    ) == []
    # 边界内仍可接受（软打分继续负责排序）
    assert [item[0]["id"] for item in lyrics.rank_matches(
        [record(id=2, duration=304 + 45)], [("夜航星", "不才")], "", 304
    )] == [2]


def test_rank_matches_rejects_instrumental_titles():
    ranked = lyrics.rank_matches(
        [record(id=1, trackName="夜航星 (伴奏)")], [("夜航星", "不才")], "", 304
    )
    assert ranked == []


def test_rank_matches_prefers_synced_on_equal_score():
    plain = record(id=1, syncedLyrics=None, plainLyrics="夜航星")
    synced = record(id=2)
    ranked = lyrics.rank_matches([plain, synced], [("夜航星", "不才")], "", 304)
    assert [item[0]["id"] for item in ranked] == [2, 1]


def test_query_plan_orders_and_dedupes():
    plan = lyrics.query_plan(
        [("夜航星", "不才"), ("不才", "夜航星")], "夜航星 - 不才"
    )
    assert plan == [
        {"track_name": "夜航星", "artist_name": "不才"},
        {"track_name": "不才", "artist_name": "夜航星"},
        {"q": "夜航星 - 不才"},
        {"track_name": "夜航星"},
    ]


def test_query_plan_skips_empty_artist_and_duplicates():
    plan = lyrics.query_plan([("夜航星", "")], "夜航星")
    # 没有歌手参数；q= 与 track_name= 是不同查询，都要保留
    assert plan == [{"track_name": "夜航星"}, {"q": "夜航星"}]


def test_query_plan_is_capped():
    plan = lyrics.query_plan(
        [("一", "甲"), ("二", "乙"), ("三", "丙")], "一 - 甲"
    )
    assert len(plan) <= lyrics.MAX_QUERIES


# ---------------------------------------------------------------- LRCLIB 查询与缓存


async def test_search_lrclib_runs_queries_in_parallel(service, monkeypatch):
    seen: list[dict] = []

    async def fake_search(params):
        seen.append(dict(params))
        await asyncio.sleep(0.2)
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    plan = lyrics.query_plan(
        [("夜航星", "不才"), ("不才", "夜航星")], "夜航星 - 不才"
    )
    started = time.perf_counter()
    ranked = await service._search_lrclib(
        [("夜航星", "不才"), ("不才", "夜航星")], "夜航星 - 不才", "", 304
    )
    elapsed = time.perf_counter() - started
    assert ranked and ranked[0][0]["id"] == record()["id"]
    assert len(seen) == len(plan)
    assert elapsed < 0.6  # 串行需要 4 × 0.2s


async def test_search_lrclib_ranks_globally_across_queries(service, monkeypatch):
    weak = record(id=1, trackName="夜航星组曲", artistName="", duration=304)
    strong = record(id=2)

    async def fake_search(params):
        return [strong] if "q" in params else [weak]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    ranked = await service._search_lrclib(
        [("夜航星", "不才")], "夜航星 - 不才", "", 304
    )
    assert [item[0]["id"] for item in ranked][:2] == [2, 1]


async def test_ladder_skips_lrclib_record_without_lyrics(service, monkeypatch):
    empty = record(id=1, syncedLyrics=None, plainLyrics=None)
    good = record(id=2)

    async def fake_search(params):
        return [empty] if "artist_name" in params else [good]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["status"] == "ok"
    assert result["source"] == "lrclib"
    assert result["meta"]["matched"]["id"] == 2


async def test_lrclib_search_results_are_cached_across_bvids(service, monkeypatch):
    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    plan = lyrics.query_plan(
        [("夜航星", "不才"), ("不才", "夜航星")], "夜航星 - 不才"
    )
    await service.resolve("BV1xx411c7mA", title="夜航星 - 不才", duration=304)
    assert calls["n"] == len(plan)
    await service.resolve("BV1xx411c7mB", title="夜航星 - 不才", duration=304)
    assert calls["n"] == len(plan)


async def test_lrclib_search_cache_expires(service, monkeypatch):
    now = {"t": 1000.0}
    monkeypatch.setattr(service, "_now", lambda: now["t"])
    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    await service.resolve("BV1xx411c7mA", title="夜航星 - 不才", duration=304)
    first = calls["n"]
    now["t"] += lyrics.SEARCH_TTL + 1
    await service.resolve("BV1xx411c7mB", title="夜航星 - 不才", duration=304)
    assert calls["n"] > first


async def test_concurrent_searches_share_inflight_requests(service, monkeypatch):
    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        await asyncio.sleep(0.05)
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    plan = lyrics.query_plan(
        [("夜航星", "不才"), ("不才", "夜航星")], "夜航星 - 不才"
    )
    await asyncio.gather(
        service.resolve("BV1xx411c7mA", title="夜航星 - 不才", duration=304),
        service.resolve("BV1xx411c7mB", title="夜航星 - 不才", duration=304),
    )
    assert calls["n"] == len(plan)


async def test_search_budget_cancels_slow_queries(service, monkeypatch):
    monkeypatch.setattr(lyrics, "LRCLIB_BUDGET", 0.1)

    async def fake_search(params):
        if "artist_name" in params:
            await asyncio.sleep(0.01)
            return [record()]
        await asyncio.sleep(5)
        return []

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    started = time.perf_counter()
    ranked = await service._search_lrclib(
        [("夜航星", "不才")], "夜航星 - 不才", "", 304
    )
    elapsed = time.perf_counter() - started
    assert ranked
    assert elapsed < 1.0


async def test_all_lrclib_timeouts_reason(service, monkeypatch):
    async def fake_search(params):
        raise httpx.ReadTimeout("超时")

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["status"] == "none"
    assert result["reason"] == "timeout"


async def test_search_budget_without_results_reports_timeout(service, monkeypatch):
    monkeypatch.setattr(lyrics, "LRCLIB_BUDGET", 0.05)

    async def slow_search(params):
        await asyncio.sleep(5)
        return []

    monkeypatch.setattr(service, "_fetch_search", slow_search)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["status"] == "none"
    assert result["reason"] == "timeout"


async def test_lrclib_uses_bili_page_duration_when_missing(service, monkeypatch):
    # 请求没带 duration 时用 B 站 page_info.duration；900s 的记录必须被硬过滤
    service.bili = FakeBili(logged_in=True, tracks=[], duration=304)

    async def fake_search(params):
        return [record(id=1, duration=900)]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    result = await service.resolve(BV, title="夜航星 - 不才")
    assert result["status"] == "none"
    assert result["reason"] == "not_found"

    async def good_search(params):
        return [record(id=2, duration=304)]

    monkeypatch.setattr(service, "_fetch_search", good_search)
    service._search_cache.clear()  # 换掉假数据后绕过上一次的查询缓存
    result = await service.resolve("BV1xx411c7mE", title="夜航星 - 不才")
    assert result["status"] == "ok"
    assert result["meta"]["matched"]["id"] == 2


# ---------------------------------------------------------------- 自动偏移校准


async def test_auto_calibration_applies_offset_from_ai_subtitle(service, monkeypatch):
    service.bili = FakeBili(
        tracks=[AI_TRACK], bodies={AI_TRACK["url"]: calibration_body(3.5)}
    )

    async def fake_search(params):
        return [calibration_record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["source"] == "lrclib"
    assert result["offset"] == pytest.approx(3.5)
    assert result["meta"]["offset_source"] == "auto"
    assert result["meta"]["auto_offset"] == pytest.approx(3.5)
    assert result["meta"]["auto_offset_confidence"] > 0
    prefs = service.preferences(BV)
    assert prefs["offset"] == pytest.approx(3.5)
    assert prefs["offset_mode"] == "auto"


async def test_manual_offset_is_not_overwritten_by_calibration(service, monkeypatch):
    service.bili = FakeBili(
        tracks=[AI_TRACK], bodies={AI_TRACK["url"]: calibration_body(3.5)}
    )
    service.update_prefs(BV, offset=2.0)

    async def fake_search(params):
        return [calibration_record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["offset"] == pytest.approx(2.0)
    assert result["meta"]["offset_source"] == "manual"
    # 校准值仍然返回，方便界面解释/排查
    assert result["meta"]["auto_offset"] == pytest.approx(3.5)
    assert service.preferences(BV)["offset"] == pytest.approx(2.0)


def test_calibration_rechecks_manual_prefs_before_writing(service):
    # 模拟：解析开始后又收到用户手动偏移，校准写盘前必须重读并让 manual 胜出
    service.update_prefs(BV, offset=2.0)
    stale = lyrics.LyricsService._default_prefs()
    record = calibration_record()
    result = lyrics._ok_result(
        "lrclib", "", [lyrics.LyricLine(40.0, "第0句歌词内容各不相同")], True
    )
    subtitle = [
        lyrics.LyricLine(43.5 + index * 5, f"第{index}句歌词内容各不相同")
        for index in range(16)
    ]
    calibrated = service._calibrate(BV, result, stale, record, subtitle)
    assert calibrated["meta"]["auto_offset"] == pytest.approx(3.5)
    assert calibrated["meta"]["offset_source"] == "manual"
    prefs = service.preferences(BV)
    assert prefs["offset"] == pytest.approx(2.0)
    assert prefs["offset_mode"] == "manual"


async def test_cached_result_keeps_auto_offset(service, monkeypatch):
    fake = FakeBili(
        tracks=[AI_TRACK], bodies={AI_TRACK["url"]: calibration_body(3.5)}
    )
    service.bili = fake

    async def fake_search(params):
        return [calibration_record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    await service.resolve(BV, title="夜航星 - 不才", duration=304)
    calls = len(fake.calls)

    second = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert second["meta"]["cached"] is True
    assert second["offset"] == pytest.approx(3.5)
    assert second["meta"]["offset_source"] == "auto"
    assert len(fake.calls) == calls


async def test_refresh_recalibrates_auto_offset(service, monkeypatch):
    body = calibration_body(3.5)
    service.bili = FakeBili(tracks=[AI_TRACK], bodies={AI_TRACK["url"]: body})

    async def fake_search(params):
        return [calibration_record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    await service.resolve(BV, title="夜航星 - 不才", duration=304)

    body["body"] = calibration_body(-4.0)["body"]
    result = await service.resolve(
        BV, title="夜航星 - 不才", duration=304, refresh=True
    )
    assert result["offset"] == pytest.approx(-4.0)
    assert result["meta"]["offset_source"] == "auto"


def test_old_prefs_without_mode_migrate(service):
    service.prefs_path(BV).parent.mkdir(parents=True, exist_ok=True)
    service.prefs_path(BV).write_text(
        json.dumps({"offset": 2.0, "pin": None}), encoding="utf-8"
    )
    assert service.preferences(BV)["offset_mode"] == "manual"

    service.prefs_path(BV).write_text(
        json.dumps({"offset": 0.0, "pin": None}), encoding="utf-8"
    )
    assert service.preferences(BV)["offset_mode"] == "auto"


def test_update_prefs_offset_mode_roundtrip(service):
    prefs = service.update_prefs(BV, offset=2.5)
    assert prefs["offset_mode"] == "manual"
    prefs = service.update_prefs(BV, offset_mode="auto")
    assert prefs["offset"] == 0.0
    assert prefs["offset_mode"] == "auto"
    with pytest.raises(ValueError):
        service.update_prefs(BV, offset_mode="bogus")


async def test_sources_reuse_resolve_snapshot(service, monkeypatch):
    fake = FakeBili(
        tracks=[AI_TRACK], bodies={AI_TRACK["url"]: calibration_body(0.0)}
    )
    service.bili = fake
    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    await service.resolve(BV, title="夜航星 - 不才", duration=304)
    search_calls = calls["n"]
    bili_calls = len(fake.calls)

    # 换源面板即使拿到不同的标题文案，也应复用刚解析出的快照
    items = await service.sources(BV, title="完全不同的标题", duration=304)
    by_source = {item["source"]: item for item in items}
    assert by_source["lrclib"]["available"] is True
    assert calls["n"] == search_calls
    assert len(fake.calls) == bili_calls





# ---------------------------------------------------------------- 字幕转换


def test_subtitle_lines_filter_placeholders_and_empty():
    body = [
        seg(0, 1, "♪ 音乐 ♪"),
        seg(1, 2, ""),
        seg(2, 3, "第一句"),
        seg(3, 4, "音乐"),
    ]
    lines = lyrics.subtitle_lines(body)
    assert [(line.t, line.text) for line in lines] == [(2.0, "第一句")]


def test_subtitle_lines_merge_ai_fragments():
    lines = lyrics.subtitle_lines(ai_body()["body"], ai=True)
    assert len(lines) == 4
    assert [(line.t, line.text) for line in lines] == [
        (0.0, "我们"),
        (10.0, "我们"),
        (20.0, "我们"),
        (30.0, "我们"),
    ]


def test_subtitle_lines_strip_ai_music_marks():
    # B 站 AI 字幕把整句歌词包在 ♪ 里：标记要去掉，且整句不再和相邻碎片合并
    body = [
        seg(0.0, 2.0, "♪ 顾不顾将相王侯 ♪"),
        seg(2.1, 4.0, "♪ 管不管万世千秋 ♪"),
        seg(4.1, 6.0, "♪ 求只求爱化解 ♪"),
        seg(6.1, 8.0, "♪ 这万丈红尘纷乱永不休 ♪"),
    ]
    lines = lyrics.subtitle_lines(body, ai=True)
    assert [line.text for line in lines] == [
        "顾不顾将相王侯",
        "管不管万世千秋",
        "求只求爱化解",
        "这万丈红尘纷乱永不休",
    ]


def test_subtitle_lines_ai_needs_four_lines():
    body = [seg(0, 1, "太"), seg(1.1, 2, "少")]
    assert lyrics.subtitle_lines(body, ai=True) == []


def test_subtitle_lines_manual_accepts_single_line():
    lines = lyrics.subtitle_lines([seg(0, 1, "唯一一句")])
    assert [(line.t, line.text) for line in lines] == [(0.0, "唯一一句")]


# ---------------------------------------------------------------- 来源阶梯


async def test_local_file_has_top_priority(service, monkeypatch):
    forbid_network(service, monkeypatch)
    service.lyrics_dir.mkdir(parents=True)
    (service.lyrics_dir / f"{BV}.lrc").write_text(
        "[00:01.00]本地第一行\n[00:02.00]本地第二行", encoding="utf-8"
    )
    result = await service.resolve(BV, title="夜航星 - 不才")
    assert result["status"] == "ok"
    assert result["source"] == "local"
    assert result["source_label"] == "本地歌词"
    assert result["synced"] is True
    assert [line["text"] for line in result["lines"]] == ["本地第一行", "本地第二行"]
    assert result["meta"]["available"]["local"] is True


async def test_pin_lrclib_is_used_without_search(service, monkeypatch):
    forbid_network(service, monkeypatch)
    service.update_prefs(BV, pin_source="lrclib", pin_id=31664182)

    async def fake_record(lrclib_id):
        assert lrclib_id == 31664182
        return record()

    monkeypatch.setattr(service, "_fetch_record", fake_record)
    result = await service.resolve(BV, duration=304)
    assert result["status"] == "ok"
    assert result["source"] == "lrclib"
    assert result["meta"]["matched"]["id"] == 31664182


async def test_pin_bili_uses_the_pinned_subtitle(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def fake_tracks(bvid):
        return [dict(MANUAL_TRACK), dict(AI_TRACK)]

    async def fake_subtitle(url):
        assert url == AI_TRACK["url"]
        return ai_body()

    monkeypatch.setattr(service, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(service, "_fetch_bili_subtitle", fake_subtitle)
    service.bili = FakeBili(logged_in=True)
    service.update_prefs(BV, pin_source="bili", pin_id=AI_TRACK["id"])

    result = await service.resolve(BV)
    assert result["status"] == "ok"
    assert result["source"] == "bili"
    assert result["source_label"] == "B 站字幕（AI 转写）"


async def test_unavailable_pin_falls_through(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def fake_tracks(bvid):
        return [dict(MANUAL_TRACK)]

    async def fake_subtitle(url):
        return manual_body()

    monkeypatch.setattr(service, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(service, "_fetch_bili_subtitle", fake_subtitle)
    service.bili = FakeBili(logged_in=True)
    service.update_prefs(BV, pin_source="lrclib", pin_id=999)

    result = await service.resolve(BV)
    assert result["source"] == "bili"
    assert result["source_label"] == "B 站字幕（人工）"


async def test_bili_manual_subtitle_precedes_lrclib(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def fake_tracks(bvid):
        return [dict(MANUAL_TRACK)]

    async def fake_subtitle(url):
        return manual_body()

    monkeypatch.setattr(service, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(service, "_fetch_bili_subtitle", fake_subtitle)
    service.bili = FakeBili(logged_in=True)

    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["source"] == "bili"
    assert result["meta"]["available"]["bili"] == "manual"


async def test_lrclib_precedes_ai_subtitles(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def fake_tracks(bvid):
        return [dict(AI_TRACK)]

    async def fake_search(params):
        return [record()]

    monkeypatch.setattr(service, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(service, "_fetch_search", fake_search)
    service.bili = FakeBili(logged_in=True)

    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["source"] == "lrclib"
    assert result["meta"]["available"]["bili"] == "ai"


async def test_ai_subtitle_is_last_resort(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def fake_tracks(bvid):
        return [dict(AI_TRACK)]

    async def fake_subtitle(url):
        return ai_body()

    async def fake_search(params):
        return []

    monkeypatch.setattr(service, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(service, "_fetch_bili_subtitle", fake_subtitle)
    monkeypatch.setattr(service, "_fetch_search", fake_search)
    service.bili = FakeBili(logged_in=True)

    result = await service.resolve(BV)
    assert result["status"] == "ok"
    assert result["source"] == "bili"
    assert result["source_label"] == "B 站字幕（AI 转写）"
    assert len(result["lines"]) == 4


async def test_ai_subtitle_prefers_ai_zh(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def fake_tracks(bvid):
        return [dict(AI_EN_TRACK), dict(AI_TRACK)]

    async def fake_subtitle(url):
        assert url == AI_TRACK["url"]
        return ai_body()

    async def fake_search(params):
        return []

    monkeypatch.setattr(service, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(service, "_fetch_bili_subtitle", fake_subtitle)
    monkeypatch.setattr(service, "_fetch_search", fake_search)
    service.bili = FakeBili(logged_in=True)

    result = await service.resolve(BV)
    assert result["source"] == "bili"
    assert result["source_label"] == "B 站字幕（AI 转写）"


async def test_all_sources_disabled_returns_disabled(service, monkeypatch):
    forbid_network(service, monkeypatch)
    monkeypatch.setattr(config.settings, "lyrics_online", False)
    monkeypatch.setattr(config.settings, "lyrics_bili", False)
    result = await service.resolve(BV)
    assert result["status"] == "none"
    assert result["reason"] == "disabled"
    assert result["lines"] == []


async def test_bili_disabled_skips_subtitles(service, monkeypatch):
    forbid_network(service, monkeypatch)
    monkeypatch.setattr(config.settings, "lyrics_bili", False)
    fake = FakeBili(logged_in=True)
    fake.error = AssertionError("关闭 lyrics_bili 后不应读取 B 站字幕")
    service.bili = fake

    async def empty_search(params):
        return []

    monkeypatch.setattr(service, "_fetch_search", empty_search)
    result = await service.resolve(BV)
    assert result["status"] == "none"
    assert fake.calls == []


async def test_online_disabled_skips_lrclib(service, monkeypatch):
    monkeypatch.setattr(config.settings, "lyrics_online", False)
    fake = FakeBili(logged_in=True, tracks=[])
    service.bili = fake

    async def boom(params):
        raise AssertionError("关闭 lyrics_online 后不应请求 LRCLIB")

    monkeypatch.setattr(service, "_fetch_search", boom)
    result = await service.resolve(BV)
    assert result["status"] == "none"
    assert result["reason"] == "not_found"


async def test_not_logged_in_reason(service, monkeypatch):
    forbid_network(service, monkeypatch)
    monkeypatch.setattr(config.settings, "lyrics_online", False)
    monkeypatch.setattr(config.settings, "lyrics_bili", True)
    fake = FakeBili(logged_in=False)
    service.bili = fake
    result = await service.resolve(BV)
    assert result["status"] == "none"
    assert result["reason"] == "not_logged_in"
    assert fake.calls == []


async def test_lrclib_timeout_reason(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def timeout(params):
        raise httpx.ReadTimeout("超时")

    monkeypatch.setattr(service, "_fetch_search", timeout)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["status"] == "none"
    assert result["reason"] == "timeout"


async def test_lrclib_generic_error_reason(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def boom(params):
        raise ValueError("坏响应")

    monkeypatch.setattr(service, "_fetch_search", boom)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["status"] == "none"
    assert result["reason"] == "error"


async def test_lrclib_plain_only_is_static(service, monkeypatch):
    forbid_network(service, monkeypatch)

    async def fake_search(params):
        return [record(syncedLyrics=None, plainLyrics="第一句\n第二句")]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["status"] == "ok"
    assert result["source"] == "lrclib"
    assert result["synced"] is False
    assert [line["text"] for line in result["lines"]] == ["第一句", "第二句"]


# ---------------------------------------------------------------- 隐私


async def test_lrclib_requests_carry_no_cookie(monkeypatch):
    seen: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["cookie"] = request.headers.get("cookie")
        seen["referer"] = request.headers.get("referer")
        seen["params"] = dict(request.url.params)
        return httpx.Response(
            200,
            json=[record()],
        )

    bili = BiliClient(
        Session(cookies={"SESSDATA": "secret", "bili_jct": "jct"}, mid=7, uname="u")
    )
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(config.settings, "lyrics_bili", False)
    monkeypatch.setattr(config.settings, "lyrics_online", True)
    service = lyrics.LyricsService(bili=bili, http=http)
    try:
        result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    finally:
        await http.aclose()
        await bili.close()

    assert result["status"] == "ok" and result["source"] == "lrclib"
    assert seen["cookie"] is None
    assert seen["referer"] is None
    assert "track_name" in seen["params"]


# ---------------------------------------------------------------- 缓存


def write_cache(service, bvid: str, result: dict, *, ttl: float = 3600) -> None:
    service.cache_dir.mkdir(parents=True, exist_ok=True)
    (service.cache_dir / f"{bvid}.json").write_text(
        json.dumps(
            {
                "v": lyrics.CACHE_VERSION,
                "expires": time.time() + ttl,
                "result": result,
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )


async def test_cache_hit_skips_fetch(service, monkeypatch):
    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    first = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert first["meta"]["cached"] is False
    assert calls["n"] == len(planned_queries("夜航星 - 不才"))

    async def boom(params):
        raise AssertionError("命中缓存后不应再请求 LRCLIB")

    monkeypatch.setattr(service, "_fetch_search", boom)
    second = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert second["source"] == "lrclib"
    assert second["meta"]["cached"] is True
    assert [line["text"] for line in second["lines"]] == ["夜航星"]


async def test_refresh_bypasses_cache(service, monkeypatch):
    async def first_search(params):
        return [record(syncedLyrics="[00:01.00]旧")]

    monkeypatch.setattr(service, "_fetch_search", first_search)
    await service.resolve(BV, title="夜航星 - 不才", duration=304)

    async def second_search(params):
        return [record(syncedLyrics="[00:01.00]新")]

    monkeypatch.setattr(service, "_fetch_search", second_search)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304, refresh=True)
    assert result["meta"]["cached"] is False
    assert [line["text"] for line in result["lines"]] == ["新"]

async def test_ok_cache_lives_thirty_days(service, monkeypatch):
    async def fake_search(params):
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    await service.resolve(BV, title="夜航星 - 不才", duration=304)
    raw = json.loads((service.cache_dir / f"{BV}.json").read_text(encoding="utf-8"))
    assert raw["v"] == lyrics.CACHE_VERSION
    ttl = raw["expires"] - time.time()
    assert 29.9 * 24 * 3600 < ttl <= lyrics.CACHE_TTL + 5


async def test_miss_cache_lives_six_hours(service, monkeypatch):
    # 只测缓存策略：关掉 B 站字幕，避免落到 not_logged_in（那种空结果不缓存）
    monkeypatch.setattr(config.settings, "lyrics_bili", False)

    async def empty(params):
        return []

    monkeypatch.setattr(service, "_fetch_search", empty)
    first = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert first["status"] == "none"
    assert first["reason"] == "not_found"
    raw = json.loads((service.cache_dir / f"{BV}.json").read_text(encoding="utf-8"))
    ttl = raw["expires"] - time.time()
    assert 5.9 * 3600 < ttl <= lyrics.MISS_TTL + 5

    async def boom(params):
        raise AssertionError("未命中缓存期内不应再请求")

    monkeypatch.setattr(service, "_fetch_search", boom)
    second = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert second["status"] == "none"
    assert second["meta"]["cached"] is True


async def test_cache_version_mismatch_is_discarded(service, monkeypatch):
    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    write_cache(
        service,
        BV,
        {
            "status": "ok",
            "source": "lrclib",
            "source_label": "LRCLIB",
            "synced": True,
            "offset": 0.0,
            "lines": [{"t": 1.0, "text": "旧缓存"}],
            "meta": {"matched": None, "available": {}, "cached": False},
            "reason": "",
        },
    )
    (service.cache_dir / f"{BV}.json").write_text(
        json.dumps({"v": 999, "expires": time.time() + 3600, "result": {"status": "ok"}}),
        encoding="utf-8",
    )
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert calls["n"] == len(planned_queries("夜航星 - 不才"))
    assert [line["text"] for line in result["lines"]] == ["夜航星"]


async def test_cached_lrclib_result_is_ignored_when_online_disabled(service, monkeypatch):
    async def fake_search(params):
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    assert (await service.resolve(BV, title="夜航星 - 不才", duration=304))["source"] == "lrclib"

    monkeypatch.setattr(config.settings, "lyrics_online", False)
    monkeypatch.setattr(config.settings, "lyrics_bili", False)
    result = await service.resolve(BV)
    assert result["status"] == "none"
    assert result["reason"] == "disabled"


# ---------------------------------------------------------------- 偏好与导入


def test_preferences_roundtrip_and_clamp(service):
    prefs = service.update_prefs(BV, offset=99)
    assert prefs["offset"] == 30.0
    prefs = service.update_prefs(BV, offset=-99)
    assert prefs["offset"] == -30.0
    prefs = service.update_prefs(BV, offset=None)
    assert prefs["offset"] == 0.0

    prefs = service.update_prefs(BV, pin_source="lrclib", pin_id=123)
    assert prefs["pin"] == {"source": "lrclib", "id": 123}
    assert service.preferences(BV)["pin"] == {"source": "lrclib", "id": 123}

    prefs = service.update_prefs(BV, pin_source=None)
    assert prefs["pin"] is None


def test_preferences_reject_bad_pin(service):
    with pytest.raises(ValueError):
        service.update_prefs(BV, pin_source="bogus")
    # LRCLIB 必须给出候选 id；B 站字幕只锁来源即可
    with pytest.raises(ValueError):
        service.update_prefs(BV, pin_source="lrclib")
    assert service.update_prefs(BV, pin_source="bili")["pin"] == {
        "source": "bili",
        "id": None,
    }


async def test_offset_applied_to_local_and_demo(service, monkeypatch):
    forbid_network(service, monkeypatch)
    service.lyrics_dir.mkdir(parents=True)
    (service.lyrics_dir / f"{BV}.lrc").write_text("[00:05.00]一句", encoding="utf-8")
    service.update_prefs(BV, offset=1.5)
    local = await service.resolve(BV)
    assert local["offset"] == 1.5
    demo = await service.resolve("DEMO0001")
    service.update_prefs("DEMO0001", offset=-2.0)
    demo = await service.resolve("DEMO0001")
    assert demo["offset"] == -2.0


async def test_import_and_delete_local(service, monkeypatch):
    forbid_network(service, monkeypatch)
    result = await service.import_lrc(BV, "[00:01.00]导入第一行\n[00:02.00]导入第二行")
    assert result["status"] == "ok"
    assert result["source"] == "local"
    path = service.lyrics_dir / f"{BV}.lrc"
    assert path.is_file()
    assert "导入第一行" in path.read_text(encoding="utf-8")

    assert (await service.resolve(BV))["source"] == "local"
    deleted = await service.delete(BV)
    assert deleted["ok"] is True
    assert not path.exists()
    assert (await service.resolve(BV))["status"] == "none"


async def test_import_rejects_oversized_and_empty(service):
    with pytest.raises(ValueError):
        await service.import_lrc(BV, "x" * (lyrics.MAX_LRC_BYTES + 1))
    with pytest.raises(ValueError):
        await service.import_lrc(BV, "   ")


async def test_search_returns_at_most_ten(service, monkeypatch):
    async def fake_search(params):
        assert params["q"] == "夜航星"
        return [record(id=i) for i in range(12)]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    items = await service.search("夜航星")
    assert len(items) == 10
    assert items[0]["id"] == 0
    assert set(items[0]) >= {
        "id",
        "track",
        "artist",
        "album",
        "duration",
        "instrumental",
        "synced",
    }


async def test_search_is_empty_without_query_or_setting(service, monkeypatch):
    async def boom(params):
        raise AssertionError("不应请求 LRCLIB")

    monkeypatch.setattr(service, "_fetch_search", boom)
    assert await service.search("  ") == []
    monkeypatch.setattr(config.settings, "lyrics_online", False)
    assert await service.search("夜航星") == []


# ---------------------------------------------------------------- 缓存不遮蔽 pin / 设置


async def test_disabled_none_result_is_not_cached(service, monkeypatch):
    monkeypatch.setattr(config.settings, "lyrics_online", False)
    monkeypatch.setattr(config.settings, "lyrics_bili", False)
    first = await service.resolve(BV)
    assert first["reason"] == "disabled"
    # 关着开关时的 none 不能缓存，否则用户重新打开后 6 小时内都看不到歌词
    assert not (service.cache_dir / f"{BV}.json").exists()

    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    monkeypatch.setattr(config.settings, "lyrics_online", True)
    second = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert second["status"] == "ok"
    assert second["source"] == "lrclib"
    assert calls["n"] == len(planned_queries("夜航星 - 不才"))


async def test_not_logged_in_none_result_is_not_cached(service, monkeypatch):
    service.bili = FakeBili(logged_in=False)
    first = await service.resolve(BV)
    assert first["reason"] == "not_logged_in"
    assert not (service.cache_dir / f"{BV}.json").exists()

    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    service.bili = None
    second = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert second["source"] == "lrclib"
    assert calls["n"] == len(planned_queries("夜航星 - 不才"))


async def test_stale_disabled_cache_is_ignored(service, monkeypatch):
    calls = {"n": 0}

    async def fake_search(params):
        calls["n"] += 1
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    write_cache(
        service,
        BV,
        {
            "status": "none",
            "source": "",
            "source_label": "",
            "synced": False,
            "offset": 0.0,
            "lines": [],
            "meta": {"matched": None, "available": {}, "cached": False},
            "reason": "disabled",
        },
    )
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["source"] == "lrclib"
    assert calls["n"] == len(planned_queries("夜航星 - 不才"))


async def test_pinned_source_skips_cache_and_retries_after_timeout(service, monkeypatch):
    forbid_network(service, monkeypatch)
    service.update_prefs(BV, pin_source="lrclib", pin_id=999)
    calls = {"n": 0}

    async def timeout(lrclib_id):
        calls["n"] += 1
        assert lrclib_id == 999
        raise httpx.ReadTimeout("超时")

    monkeypatch.setattr(service, "_fetch_record", timeout)
    first = await service.resolve(BV)
    assert first["status"] == "none"
    assert calls["n"] == 1
    # pin 降级结果不能写缓存，否则之后永远不会再试 pin
    assert not (service.cache_dir / f"{BV}.json").exists()

    second = await service.resolve(BV)
    assert calls["n"] == 2
    assert second["meta"]["pin"] == {"source": "lrclib", "id": 999}


async def test_meta_pin_reports_locked_source(service, monkeypatch):
    forbid_network(service, monkeypatch)
    service.update_prefs(BV, pin_source="lrclib", pin_id=31664182)

    async def fake_record(lrclib_id):
        return record()

    monkeypatch.setattr(service, "_fetch_record", fake_record)
    result = await service.resolve(BV, duration=304)
    assert result["status"] == "ok"
    assert result["meta"]["pin"] == {"source": "lrclib", "id": 31664182}


async def test_meta_pin_is_null_without_pin_even_when_cached(service, monkeypatch):
    async def fake_search(params):
        return [record()]

    monkeypatch.setattr(service, "_fetch_search", fake_search)
    first = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert first["meta"]["pin"] is None
    second = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert second["meta"]["cached"] is True
    assert second["meta"]["pin"] is None


async def test_not_logged_in_reason_when_online_finds_nothing(service, monkeypatch):
    service.bili = FakeBili(logged_in=False)

    async def empty_search(params):
        return []

    monkeypatch.setattr(service, "_fetch_search", empty_search)
    result = await service.resolve(BV, title="夜航星 - 不才", duration=304)
    assert result["status"] == "none"
    assert result["reason"] == "not_logged_in"


async def test_lrclib_response_size_is_capped(tmp_path):
    huge = b"x" * (lyrics.MAX_REMOTE_BYTES + 1)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=huge)

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    service = lyrics.LyricsService(
        bili=None,
        http=http,
        lyrics_dir=tmp_path / "lyrics",
        cache_dir=tmp_path / "cache",
    )
    try:
        with pytest.raises(lyrics.LyricsError):
            await service._fetch_search({"q": "夜航星"})
        with pytest.raises(lyrics.LyricsError):
            await service._fetch_record(1)
    finally:
        await http.aclose()


async def test_lock_table_is_pruned(service, monkeypatch):
    monkeypatch.setattr(config.settings, "lyrics_online", False)
    monkeypatch.setattr(config.settings, "lyrics_bili", False)
    for index in range(300):
        service._locks[f"BV{index:08d}"] = asyncio.Lock()
    await service.resolve(BV)
    assert len(service._locks) <= 257
    assert BV in service._locks
