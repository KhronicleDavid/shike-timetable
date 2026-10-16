"""用已有 IndexTTS-2.5 环境离线生成希尔维娅分时语音；不安装依赖或修改全局音色。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tts-root", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True, help="用户提供的完整、清晰的单句声音文件")
    parser.add_argument("--preview-dir", type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    root = args.tts_root.resolve()
    work = project / "work/sylvia-voice-release"
    work.mkdir(parents=True, exist_ok=True)
    pack = project / "app/src/main/assets/themes/sylvia"
    output = pack / "voice"
    output.mkdir(exist_ok=True)
    script = json.loads((project / "docs/希尔维娅语音台本.json").read_text(encoding="utf-8"))
    theme_file = pack / "theme.json"
    theme = json.loads(theme_file.read_text(encoding="utf-8"))
    source = args.reference.resolve()
    os.environ.setdefault("USE_MODELSCOPE", "true")
    import soundfile as sf
    import numpy as np
    data, rate = sf.read(source)
    if data.ndim == 2:
        data = data.mean(axis=1)
    assert 3 < len(data) / rate < 12 and np.isfinite(data).all(), "参考音频应为清晰的完整单句"
    reference = work / "reference.wav"
    sf.write(reference, data, rate, subtype="PCM_16")
    samples = []
    for period in ("morning", "noon", "evening"):
        lines = theme["companion"]["quotes"][period]
        assert len(lines) == len(script[period]) and lines, "中文语录和日语台本须逐一对应"
        for i, (zh, ja) in enumerate(zip(lines, script[period])):
            samples.append(dict(line=zh, text=ja, language="JA", file=f"voice/{period}_{i+1:03d}.ogg"))
    previews = {}
    if args.preview_dir:
        preview_manifest = json.loads((args.preview_dir / "试听清单.json").read_text(encoding="utf-8"))
        previews = {r["text_ja"]: args.preview_dir / r["file"] for r in preview_manifest["samples"]}

    import msvcrt
    with (root / "_local/codex_speech.lock").open("a+b") as lock:
        lock.seek(0, 2)
        if lock.tell() == 0:
            lock.write(b"0"); lock.flush()
        deadline = time.monotonic() + 600
        while True:
            lock.seek(0)
            try:
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError:
                if time.monotonic() > deadline:
                    raise TimeoutError("Speech GPU lock busy")
                time.sleep(1)
        try:
            os.chdir(root)
            sys.path.insert(0, str(root))
            from indextts.infer_v2_5 import IndexTTS2
            import torch
            import random
            tts = IndexTTS2(cfg_path=str(root / "checkpoints/config.yaml"), model_dir=str(root / "checkpoints"),
                            use_bf16=True, use_qwen_emo=False, use_cuda_kernel=False)
            records = []
            for i, clip in enumerate(samples):
                key = hashlib.sha256((clip["text"] + hashlib.sha256(reference.read_bytes()).hexdigest()).encode("utf-8")).hexdigest()[:12]
                raw = previews.get(clip["text"], work / (Path(clip["file"]).stem + "-" + key + ".wav"))
                if not raw.is_file():
                    seed = 26101600 + i
                    random.seed(seed); np.random.seed(seed); torch.manual_seed(seed)
                    torch.cuda.manual_seed_all(seed)
                    tts.infer(spk_audio_prompt=str(reference), text=clip["text"], lang="JA",
                              output_path=str(raw), verbose=False, max_mel_tokens=1000)
                audio, sr = sf.read(raw)
                seconds = len(audio) / sr
                assert 1 < seconds < 20 and np.isfinite(audio).all() and np.abs(audio).max() > .01, clip
                # Keep a little peak headroom; no speed/pitch transformation.
                audio *= min(1.0, .90 / max(float(np.abs(audio).max()), 1e-9))
                target = pack / clip["file"]
                sf.write(target, audio, sr, format="OGG", subtype="VORBIS")
                decoded, decoded_sr = sf.read(target)
                assert decoded_sr == sr and len(decoded) == len(audio) and np.isfinite(decoded).all()
                records.append(dict(**clip, seconds=round(seconds, 3), sampleRate=sr,
                                    sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
                (work / "progress.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
                print(f"VOICE {i+1}/{len(samples)} {clip['file']} {seconds:.2f}s", flush=True)
            theme["companion"]["voices"] = samples
            theme["version"] = 2
            theme_file.write_text(json.dumps(theme, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            manifest = dict(engine="IndexTTS-2.5", synthetic=True, language="JA", reference=dict(
                sourceFile=source.name, sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                seconds=round(len(data)/rate, 3), sha256=hashlib.sha256(reference.read_bytes()).hexdigest()),
                clips=records)
            (project / "docs/希尔维娅语音生成记录.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"ALL_DONE {len(samples)}", flush=True)
        finally:
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)


if __name__ == "__main__":
    main()
