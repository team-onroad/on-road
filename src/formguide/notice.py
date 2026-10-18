"""정책·지원 공고 페이지에서 첨부파일(HWP/HWPX/PDF)을 찾고, 신청서로 보이는 파일을 고른다.

AI 없이 규칙으로 처리한다 (API 키 불필요).
- 링크 주소나 링크 글자에 .hwp/.hwpx/.pdf 가 있으면 첨부파일로 본다.
  공공기관 사이트는 다운로드 주소에 확장자가 없는 경우가 많아(예: /temp/dnd.do?fileKey=...) 링크 글자의 파일 이름을 쓴다.
- 다운로드가 자바스크립트(onclick 등)로만 되는 사이트는 주소를 알 수 없어 "자동으로 받을 수 없음"으로 알려 준다.
- 파일 이름의 단어(신청서·서식·양식 > 붙임 > 안내·공고문)로 신청서 순위를 매긴다.

실행 (src 폴더에서):
    backend\\.venv\\Scripts\\python -m formguide.notice "https://www.ncrc.or.kr/ncrc/na/ntt/selectNttInfo.do?mi=1177&nttSn=10594"
"""

import argparse
import re
import urllib.parse
import urllib.request
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path

from formguide.fetch import ALLOWED_SUFFIXES, USER_AGENT, fetch

MAX_PAGE_BYTES = 5 * 1024 * 1024
_TIMEOUT = 20
_NAME_RE = re.compile(r"[^\s/\\:*?\"<>|]+(?:\s+[^\s/\\:*?\"<>|]+)*?\.(?:hwpx|hwp|pdf)\b", re.I)
# (단어, 점수). 파일 이름에 있으면 더한다
_KEYWORDS = (
    ("신청서", 10), ("서식", 6), ("양식", 6), ("신청", 4), ("붙임", 2), ("별지", 3),
    ("안내", -2), ("공고문", -3), ("공고", -1), ("매뉴얼", -4), ("정보북", -4), ("결과", -4),
)


class NoticeError(RuntimeError):
    pass


@dataclass(frozen=True)
class Attachment:
    name: str  # 파일 이름 (링크 글자 또는 주소에서)
    url: str | None  # 내려받을 주소. 자바스크립트 다운로드면 None
    score: int

    @property
    def downloadable(self) -> bool:
        return self.url is not None


class _LinkParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links: list[tuple[dict, str]] = []
        self._stack: list[tuple[dict, list[str]]] = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self._stack.append((dict(attrs), []))

    def handle_data(self, data):
        for _, texts in self._stack:
            texts.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self._stack:
            attrs, texts = self._stack.pop()
            self.links.append((attrs, " ".join(" ".join(texts).split())))


def score_name(name: str) -> int:
    return sum(s for word, s in _KEYWORDS if word in name) + (1 if name.lower().endswith((".hwp", ".hwpx")) else 0)


def _name_in(text: str) -> str | None:
    m = _NAME_RE.search(text.replace("\xa0", " "))
    return m.group(0).strip() if m else None


def find_attachments(html: str, page_url: str) -> list[Attachment]:
    parser = _LinkParser()
    parser.feed(html)
    found: dict[str, Attachment] = {}
    for attrs, text in parser.links:
        href = (attrs.get("href") or "").strip()
        is_js = href.lower().startswith("javascript:") or (href in ("", "#") and "onclick" in attrs)
        url_name = _name_in(urllib.parse.unquote(urllib.parse.urlparse(href).path)) if href and not is_js else None
        name = _name_in(text) or url_name or _name_in(attrs.get("title") or "")
        if name is None:
            continue
        url = None if is_js or not href else urllib.parse.urljoin(page_url, href)
        if url is not None and urllib.parse.urlparse(url).scheme not in ("http", "https"):
            url = None
        key = url or f"js:{name}"
        if key not in found:
            found[key] = Attachment(name=name, url=url, score=score_name(name))
    # 신청서 점수 높은 순, 같으면 페이지에 나온 순서
    return sorted(found.values(), key=lambda a: -a.score)


def _decode(data: bytes, content_type: str | None) -> str:
    m = re.search(r"charset=([\w-]+)", content_type or "", re.I) or re.search(
        rb"<meta[^>]+charset=[\"']?([\w-]+)", data[:4096], re.I
    )
    charset = m.group(1) if m else "utf-8"
    if isinstance(charset, bytes):
        charset = charset.decode("ascii", "ignore")
    for enc in (charset, "utf-8", "cp949"):
        try:
            return data.decode(enc)
        except (LookupError, UnicodeDecodeError):
            continue
    return data.decode("utf-8", "replace")


def load_page(page_url: str) -> str:
    if not page_url.lower().startswith(("http://", "https://")):
        raise NoticeError("공고 페이지 주소는 http(s) 로 시작해야 합니다.")
    req = urllib.request.Request(page_url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        data = resp.read(MAX_PAGE_BYTES)
        return _decode(data, resp.headers.get("Content-Type"))


def pick_form(attachments: list[Attachment]) -> Attachment | None:
    """내려받을 수 있는 첨부 중 신청서 점수가 가장 높은 것."""
    return next((a for a in attachments if a.downloadable), None)


def download(att: Attachment, dest_dir: Path) -> Path:
    if att.url is None:
        raise NoticeError(f"'{att.name}' 은 자바스크립트 다운로드라 자동으로 받을 수 없습니다. 브라우저에서 직접 받아 주세요.")
    return fetch(att.url, dest_dir, suggested_name=att.name)


def main() -> None:
    parser = argparse.ArgumentParser(description="공고 페이지의 첨부파일 찾기")
    parser.add_argument("page_url")
    parser.add_argument("--download", type=int, metavar="N", help="목록의 N번 파일을 data/forms/_work 에 내려받기")
    args = parser.parse_args()

    attachments = find_attachments(load_page(args.page_url), args.page_url)
    if not attachments:
        print(f"첨부파일({', '.join(ALLOWED_SUFFIXES)})을 찾지 못했습니다.")
        return
    for i, a in enumerate(attachments, 1):
        mark = "" if a.downloadable else "  (자동 다운로드 불가: 자바스크립트)"
        print(f"{i}. [신청서 점수 {a.score:+d}] {a.name}{mark}")
    if args.download:
        from formguide.build import FORMS_DIR

        path = download(attachments[args.download - 1], FORMS_DIR / "_work")
        print(f"저장: {path}")


if __name__ == "__main__":
    main()
