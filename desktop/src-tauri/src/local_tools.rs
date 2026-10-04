use serde::Serialize;
use std::{env, path::PathBuf};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalTools {
    pub adb_path: Option<String>,
    pub apk_path: Option<String>,
}

fn first_adb(candidates: impl IntoIterator<Item = PathBuf>) -> Option<String> {
    candidates.into_iter().take(256).find_map(|path| {
        if !path.is_absolute()
            || !path.is_file()
            || !matches!(
                path.file_name().and_then(|name| name.to_str()),
                Some("adb" | "adb.exe")
            )
        {
            return None;
        }
        path.to_str().map(str::to_owned)
    })
}

/// Reads known tool locations only. Finding a tool never launches it or scans a phone.
pub fn discover(companion: Option<PathBuf>) -> LocalTools {
    let executable = if cfg!(windows) { "adb.exe" } else { "adb" };
    let mut candidates = Vec::new();
    for variable in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(root) = env::var_os(variable) {
            candidates.push(PathBuf::from(root).join("platform-tools").join(executable));
        }
    }
    if let Some(local) = env::var_os("LOCALAPPDATA") {
        candidates.push(
            PathBuf::from(local)
                .join("Android/Sdk/platform-tools")
                .join(executable),
        );
    }
    if let Some(path) = env::var_os("PATH") {
        candidates.extend(
            env::split_paths(&path)
                .take(128)
                .map(|folder| folder.join(executable)),
        );
    }
    LocalTools {
        adb_path: first_adb(candidates),
        apk_path: companion
            .filter(|path| path.is_absolute() && path.is_file())
            .and_then(|path| path.to_str().map(str::to_owned)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn discovery_rejects_relative_missing_and_non_adb_files_and_preserves_priority() {
        let folder = env::temp_dir().join(format!("localspoof-discovery-{}", std::process::id()));
        fs::create_dir_all(&folder).unwrap();
        let wrong = folder.join("other.exe");
        let preferred = folder.join("adb.exe");
        let fallback_folder = folder.join("fallback");
        fs::create_dir_all(&fallback_folder).unwrap();
        let fallback = fallback_folder.join("adb");
        for file in [&wrong, &preferred, &fallback] {
            fs::write(file, b"synthetic discovery fixture").unwrap();
        }
        assert_eq!(
            first_adb([
                PathBuf::from("adb.exe"),
                folder.join("missing/adb.exe"),
                wrong,
                preferred.clone(),
                fallback
            ]),
            Some(preferred.to_str().unwrap().to_owned())
        );
        fs::remove_dir_all(folder).unwrap();
    }
}
