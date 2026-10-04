fn main() {
    println!("cargo:rerun-if-env-changed=LOCALSPOOF_TRUSTED_APK_SHA256");
    tauri_build::build()
}
