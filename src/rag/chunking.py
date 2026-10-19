"""정책 원문을 Chroma 에 넣을 청크로 나눈다.

original_text 는 데이터 담당 작성 기준(data/policies/README.md)에 따라 여러 출처를 한 필드에 넣고,
출처 머리말 `[출처 A: 복지로]` 과 항목 머리말 `[지원대상]`, `[신청방법]` 등으로 구분한다.
청크는 (출처, 항목) 단위로 만들고, 짧은 항목은 다음 항목과 합치고, 너무 긴 항목은 줄 단위로 나눈다.
머리말이 없는 원문(더미 정책 등)은 원문 전체를 한 항목으로 본다.
"""

import re
from dataclasses import dataclass

_SOURCE_RE = re.compile(r"^\[출처\s*([^:\]]+):\s*([^\]]+)\]$")
_SECTION_RE = re.compile(r"^\[([^\[\]]+)\]$")
MAX_CHARS = 700
MIN_CHARS = 150
_NO_SECTION = "원문"
APPLY_SECTIONS = ("신청방법", "신청서류", "처리절차")


@dataclass(frozen=True)
class Chunk:
    id: str
    policy_key: str
    policy_name: str
    source: str  # 예: "A: 복지로". 출처 머리말이 없으면 ""
    section: str  # 예: "지원대상". 짧은 항목을 합친 청크는 "정책명·정책 개요"
    content: str  # 근거로 그대로 보여줄 원문 스니펫

    @property
    def embed_text(self) -> str:
        """임베딩할 텍스트. 정책명을 붙여 질문의 정책명과도 맞을 수 있게 한다."""
        return f"{self.policy_name}\n{self.content}"

    @property
    def metadata(self) -> dict:
        return {
            "policy_key": self.policy_key,
            "policy_name": self.policy_name,
            "source": self.source,
            "section": self.section,
            "is_apply": self.is_apply,
        }

    @property
    def is_apply(self) -> bool:
        """신청 방법·서류·절차를 담은 청크. 검색 결과에 신청 안내를 함께 넣을 때 쓴다."""
        return any(k in self.section for k in APPLY_SECTIONS)


def _split_long(lines: list[str]) -> list[str]:
    parts: list[str] = []
    buf: list[str] = []
    size = 0
    for line in lines:
        if buf and size + len(line) > MAX_CHARS:
            parts.append("\n".join(buf))
            buf, size = [], 0
        # 한 줄이 MAX_CHARS 보다 길면 글자 수로 자른다
        while len(line) > MAX_CHARS:
            parts.append(line[:MAX_CHARS])
            line = line[MAX_CHARS:]
        buf.append(line)
        size += len(line) + 1
    if buf:
        if parts and size < MIN_CHARS:
            parts[-1] += "\n" + "\n".join(buf)  # 짧은 꼬리는 앞 조각에 붙인다
        else:
            parts.append("\n".join(buf))
    return parts


def _sections(original_text: str) -> list[tuple[str, str, list[str]]]:
    """(출처, 항목, 본문 줄) 목록. 출처 머리말 바로 뒤 항목 전 부분(URL·쪽수)은 버린다."""
    out: list[tuple[str, str, list[str]]] = []
    source = ""
    section: str | None = None
    body: list[str] = []
    has_header = False

    def flush():
        if section is not None and any(b.strip() for b in body):
            out.append((source, section, body))

    for raw in original_text.splitlines():
        line = raw.strip()
        if m := _SOURCE_RE.match(line):
            flush()
            source, section, body = f"{m.group(1).strip()}: {m.group(2).strip()}", None, []
            has_header = True
        elif m := _SECTION_RE.match(line):
            flush()
            section, body = m.group(1).strip(), []
            has_header = True
        elif line:
            body.append(line)
    flush()

    if not has_header and original_text.strip():
        return [("", _NO_SECTION, [l.strip() for l in original_text.splitlines() if l.strip()])]
    return out


def chunk_policy(policy: dict) -> list[Chunk]:
    key = policy["policy_key"]
    name = policy["name"]
    chunks: list[Chunk] = []
    for source, lines in _merge_short(_sections(policy.get("original_text") or "")):
        header = ""
        for part in _split_long(lines):
            part_lines = part.split("\n")
            headers = [l for l in part_lines if _SECTION_RE.match(l)]
            if not _SECTION_RE.match(part_lines[0]):
                part = f"{header}\n{part}"  # 긴 항목이 잘린 뒷부분에도 항목 머리말을 붙인다
                headers = [header, *headers]
            header = headers[-1]
            section = "·".join(h[1:-1] for h in headers)
            chunks.append(Chunk(f"{key}-{len(chunks):03d}", key, name, source, section, part))
    return chunks


def _merge_short(sections: list[tuple[str, str, list[str]]]) -> list[tuple[str, list[str]]]:
    """(출처, 머리말 포함 줄) 목록. 같은 출처 안에서 짧은 항목(문의처·기준연도 등)은 다음 항목과 합친다.

    짧은 항목만 따로 두면 내용이 거의 정책명뿐이라 어떤 질문에도 가깝게 검색되기 때문이다.
    """
    merged: list[tuple[str, list[str]]] = []
    for source, section, body in sections:
        lines = [f"[{section}]", *body]
        prev_short = merged and sum(len(l) for l in merged[-1][1]) < MIN_CHARS
        this_short = sum(len(l) for l in lines) < MIN_CHARS
        if merged and merged[-1][0] == source and (prev_short or this_short):
            merged[-1][1].extend(lines)
        else:
            merged.append((source, lines))
    return merged
