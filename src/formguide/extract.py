"""PDF 에서 글자와 표를 뽑는다 (pdfplumber). 신청서는 대부분 표 양식이라 표를 따로 뽑는다."""

import re
from dataclasses import dataclass, field
from pathlib import Path

import pdfplumber

MIN_TEXT_CHARS = 20


class ExtractError(RuntimeError):
    pass


@dataclass
class Page:
    number: int
    text: str
    tables: list[list[list[str]]] = field(default_factory=list)


@dataclass
class FormText:
    pages: list[Page]
    # 표 구조로 찾은 칸 이름 후보 (formguide/layout.label_hints). LLM 이 칸을 빠뜨리지 않게 같이 준다
    hints: list[str] = field(default_factory=list)

    def as_prompt_text(self) -> str:
        """LLM 에 줄 텍스트. 표는 행마다 ' | ' 로 이어 칸 이름과 칸의 관계가 보이게 한다."""
        out = []
        for p in self.pages:
            out.append(f"=== {p.number}쪽 ===")
            out.append(p.text)
            for i, table in enumerate(p.tables, 1):
                out.append(f"--- {p.number}쪽 표 {i} ---")
                out.extend(" | ".join(c for c in row) for row in table)
        return "\n".join(out)

    def normalized(self) -> str:
        """원문 대조용: 공백을 모두 지운 전체 텍스트."""
        return normalize(self.as_prompt_text())


def normalize(text: str) -> str:
    return re.sub(r"\s+", "", text)


def _clean_table(table: list[list[str | None]]) -> list[list[str]]:
    rows = []
    for row in table:
        cells = [re.sub(r"\s+", " ", c or "").strip() for c in row]
        if any(cells):
            rows.append(cells)
    return rows


def extract(pdf_path: Path) -> FormText:
    pages = []
    with pdfplumber.open(pdf_path) as pdf:
        for i, page in enumerate(pdf.pages, 1):
            text = page.extract_text() or ""
            tables = [t for t in (_clean_table(t) for t in page.extract_tables()) if t]
            pages.append(Page(i, text.strip(), tables))
    total = sum(len(normalize(p.text)) for p in pages)
    if total < MIN_TEXT_CHARS:
        raise ExtractError("PDF 에서 글자를 거의 찾지 못했습니다. 스캔본이면 OCR 이 필요합니다. 원본 HWP 에서 변환한 PDF 를 써 주세요.")
    return FormText(pages)
