"""HWP/HWPX → PDF 변환. PDF 는 그대로 쓴다.

변환 방법 (위에서부터 시도):
1. 한글 프로그램 자동화 (Windows + 한글 설치 필요, pywin32). 처음 실행 때 한글이 "파일 접근 허용" 창을
   띄울 수 있다 → 허용을 누르면 된다. 서버가 아니라 데이터 준비용 PC 에서만 쓴다.
2. LibreOffice (soffice) + H2Orestart 확장. 한글이 없는 PC 용.
둘 다 없으면 한글에서 직접 "PDF로 저장" 한 파일을 넣으면 된다.
"""

import shutil
import subprocess
from pathlib import Path


class ConvertError(RuntimeError):
    pass


def to_pdf(src: Path, out_dir: Path) -> Path:
    suffix = src.suffix.lower()
    if suffix == ".pdf":
        return src
    if suffix not in (".hwp", ".hwpx"):
        raise ConvertError(f"변환할 수 없는 형식입니다: {suffix}")
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{src.stem}.pdf"

    errors = []
    for convert in (_with_hangul, _with_libreoffice):
        try:
            convert(src.resolve(), out.resolve())
            if out.exists() and out.stat().st_size > 0:
                return out
            errors.append(f"{convert.__name__}: 결과 파일이 없음")
        except Exception as e:  # 다음 방법으로 넘어간다
            errors.append(f"{convert.__name__}: {type(e).__name__}: {e}")
    raise ConvertError(
        "HWP → PDF 변환에 실패했습니다. 한글에서 'PDF로 저장' 한 파일을 넣어 주세요.\n" + "\n".join(errors)
    )


def _with_hangul(src: Path, out: Path) -> None:
    import win32com.client  # pywin32, Windows 전용

    hwp = win32com.client.Dispatch("HWPFrame.HwpObject")
    try:
        try:
            hwp.XHwpWindows.Item(0).Visible = False
        except Exception:
            pass
        # 보안 모듈이 등록돼 있으면 파일 접근 확인 창을 건너뛴다 (없으면 창이 뜰 수 있음)
        try:
            hwp.RegisterModule("FilePathCheckDLL", "FilePathCheckerModule")
        except Exception:
            pass
        if not hwp.Open(str(src), "", "forceopen:true"):
            raise ConvertError("한글에서 파일을 열지 못했습니다.")
        if not hwp.SaveAs(str(out), "PDF", ""):
            raise ConvertError("한글에서 PDF 로 저장하지 못했습니다.")
    finally:
        hwp.Quit()


def _with_libreoffice(src: Path, out: Path) -> None:
    soffice = shutil.which("soffice") or shutil.which("soffice.exe")
    if soffice is None:
        candidate = Path(r"C:\Program Files\LibreOffice\program\soffice.exe")
        soffice = str(candidate) if candidate.exists() else None
    if soffice is None:
        raise ConvertError("LibreOffice(soffice)가 없습니다.")
    subprocess.run(
        [soffice, "--headless", "--convert-to", "pdf", "--outdir", str(out.parent), str(src)],
        check=True, capture_output=True, timeout=120,
    )
    produced = out.parent / f"{src.stem}.pdf"
    if produced != out and produced.exists():
        produced.replace(out)
