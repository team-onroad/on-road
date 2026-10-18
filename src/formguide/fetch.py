"""정책 신청서 파일 가져오기: 로컬 경로는 그대로, http(s) URL 은 내려받는다."""

import re
import urllib.parse
import urllib.request
from pathlib import Path

ALLOWED_SUFFIXES = (".pdf", ".hwp", ".hwpx")
MAX_BYTES = 30 * 1024 * 1024
_TIMEOUT = 30
# 일부 공공기관 사이트는 브라우저가 아니면 막는다
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) On-Road formguide"


class FetchError(RuntimeError):
    pass


def _fix_header_text(text: str) -> str:
    """http.client 는 헤더를 latin-1 로 읽는다. 한글 파일명(UTF-8·CP949 원바이트)을 되살린다."""
    try:
        raw = text.encode("latin-1")
    except UnicodeEncodeError:
        return text
    for enc in ("utf-8", "cp949"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return text


def _filename_from(url: str, content_disposition: str | None) -> str | None:
    if content_disposition:
        m = re.search(r"filename\*=UTF-8''([^;]+)", content_disposition, re.I)
        if m:
            return urllib.parse.unquote(m.group(1))
        m = re.search(r"filename=\"?([^\";]+)\"?", content_disposition, re.I)
        if m:
            return urllib.parse.unquote(_fix_header_text(m.group(1).strip()))
    name = Path(urllib.parse.urlparse(url).path).name
    return name if Path(name).suffix.lower() in ALLOWED_SUFFIXES else None


def fetch(source: str, dest_dir: Path, suggested_name: str | None = None) -> Path:
    """source 가 파일 경로면 그대로 돌려주고, URL 이면 dest_dir 에 내려받은 경로를 돌려준다.

    suggested_name: 공고 페이지 링크 글자에 적힌 파일 이름. 서버가 파일 이름을 안 알려줄 때 쓴다.
    """
    if not source.lower().startswith(("http://", "https://")):
        path = Path(source)
        if not path.exists():
            raise FetchError(f"파일이 없습니다: {path}")
        _check_suffix(path)
        return path

    req = urllib.request.Request(source, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        name = _filename_from(source, resp.headers.get("Content-Disposition")) or suggested_name or "download"
        data = resp.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise FetchError(f"파일이 {MAX_BYTES // 1024 // 1024}MB 보다 큽니다.")
    path = dest_dir / Path(name.replace("\\", "/")).name  # 경로 조작 방지: 파일 이름만 쓴다
    _check_suffix(path)
    dest_dir.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def _check_suffix(path: Path) -> None:
    if path.suffix.lower() not in ALLOWED_SUFFIXES:
        raise FetchError(f"지원하지 않는 형식입니다: {path.suffix or '(확장자 없음)'} (가능: {', '.join(ALLOWED_SUFFIXES)})")
