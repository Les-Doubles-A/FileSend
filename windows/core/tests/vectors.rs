//! Vecteurs partages avec l'implementation Kotlin (protocol-tests/vectors.json).

use filesend_core::events::FailReason;
use filesend_core::paths::{sanitize_relative_path, unique_top_name};
use filesend_core::protocol::{PrepareUploadRequest, PrepareUploadResponse};
use serde_json::Value;

fn vectors() -> Value {
    let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../protocol-tests/vectors.json");
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

fn generated(v: &Value) -> String {
    let unit = v["unit"].as_str().unwrap();
    let times = v["times"].as_u64().unwrap() as usize;
    vec![unit; times].join(v["join"].as_str().unwrap())
}

#[test]
fn valid_paths() {
    let v = vectors();
    for c in v["pathsValid"].as_array().unwrap() {
        let input = c["input"].as_str().unwrap();
        let want: Vec<String> =
            c["components"].as_array().unwrap().iter().map(|x| x.as_str().unwrap().into()).collect();
        assert_eq!(sanitize_relative_path(input).unwrap(), want, "{input:?}");
    }
    for g in v["pathsValidGenerated"].as_array().unwrap() {
        assert!(sanitize_relative_path(&generated(g)).is_ok(), "{}", g["why"]);
    }
}

#[test]
fn invalid_paths() {
    let v = vectors();
    for s in v["pathsInvalid"].as_array().unwrap() {
        let input = s.as_str().unwrap();
        assert!(sanitize_relative_path(input).is_err(), "devrait refuser {input:?}");
    }
    for g in v["pathsInvalidGenerated"].as_array().unwrap() {
        assert!(sanitize_relative_path(&generated(g)).is_err(), "{}", g["why"]);
    }
}

#[test]
fn collisions() {
    let v = vectors();
    for c in v["collisions"].as_array().unwrap() {
        let d = tempfile::tempdir().unwrap();
        for e in c["existing"].as_array().unwrap() {
            std::fs::write(d.path().join(e.as_str().unwrap()), "x").unwrap();
        }
        let name = c["name"].as_str().unwrap();
        assert_eq!(unique_top_name(d.path(), name), c["expected"].as_str().unwrap(), "{c}");
    }
}

#[test]
fn prepare_upload_json() {
    let v = vectors();
    let req: PrepareUploadRequest = serde_json::from_value(v["prepareUpload"]["request"].clone()).unwrap();
    assert_eq!(req.info.alias, "Mon PC");
    assert_eq!(req.files["f0"].relative_path, "photos/a.jpg");
    assert_eq!(req.files["f0"].sha256.as_deref(), Some("00ff"));
    assert_eq!(req.files["f1"].sha256, None);
    let resp: PrepareUploadResponse =
        serde_json::from_value(v["prepareUpload"]["response"].clone()).unwrap();
    assert_eq!(resp.files["f1"], "tok-1");
}

#[test]
fn fail_reason_texts() {
    let v = vectors();
    let want: Vec<&str> = v["failReasons"].as_array().unwrap().iter().map(|x| x.as_str().unwrap()).collect();
    let got: Vec<String> =
        [FailReason::Refused, FailReason::NotFound, FailReason::Interrupted, FailReason::DiskFull]
            .iter()
            .map(|r| r.message())
            .collect();
    assert_eq!(got, want);
}

#[test]
fn message_texts() {
    use filesend_core::messages::*;
    let v = vectors();
    let m = &v["messages"];
    for c in m["formatSize"].as_array().unwrap() {
        assert_eq!(format_size(c["bytes"].as_u64().unwrap()), c["text"].as_str().unwrap(), "{c}");
    }
    for c in m["request"].as_array().unwrap() {
        let (t, d) = request(
            c["alias"].as_str().unwrap(),
            c["count"].as_u64().unwrap() as usize,
            c["total"].as_u64().unwrap(),
        );
        assert_eq!(t, c["title"].as_str().unwrap());
        assert_eq!(d, c["detail"].as_str().unwrap());
    }
    let s = &m["status"];
    let a = s["alias"].as_str().unwrap();
    assert_eq!(sending(a), s["sending"].as_str().unwrap());
    assert_eq!(sent(a), s["sent"].as_str().unwrap());
    assert_eq!(receiving(a), s["receiving"].as_str().unwrap());
    assert_eq!(received(a), s["received"].as_str().unwrap());
    for c in m["percent"].as_array().unwrap() {
        assert_eq!(percent(c["done"].as_u64().unwrap(), c["total"].as_u64().unwrap()) as u64, c["percent"].as_u64().unwrap());
    }
}
