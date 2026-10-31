fn main() {
    // 边车文件名带 target triple；编译期把 triple 固化进来，运行时拼路径。
    println!(
        "cargo:rustc-env=BILIMUSE_TARGET={}",
        std::env::var("TARGET").unwrap_or_default()
    );
    tauri_build::build()
}
