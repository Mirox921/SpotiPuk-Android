"""Render the README badges from local version metadata and GitHub counts."""

import argparse
from html import escape
from pathlib import Path
import re


def write_badge(directory, name, label, value, accent):
    left_width = max(62, len(label) * 7 + 24)
    right_width = max(42, len(value) * 7 + 24)
    width = left_width + right_width
    label, value = escape(label), escape(value)
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="28" role="img" aria-label="{label}: {value}">
  <title>{label}: {value}</title>
  <rect width="{width}" height="28" rx="5" fill="#23252e"/>
  <path d="M{left_width} 0 H{width - 5} Q{width} 0 {width} 5 V23 Q{width} 28 {width - 5} 28 H{left_width} Z" fill="{accent}"/>
  <g font-family="Verdana, sans-serif" font-size="11" font-weight="bold" text-anchor="middle" dominant-baseline="middle">
    <text x="{left_width / 2}" y="15" fill="#ffffff">{label}</text>
    <text x="{left_width + right_width / 2}" y="15" fill="#16151a">{value}</text>
  </g>
</svg>
"""
    (directory / f"{name}.svg").write_text(svg, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stars", type=int)
    parser.add_argument("downloads", type=int)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    config = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', config, flags=re.MULTILINE)
    if not version:
        parser.error("Could not find versionName in app/build.gradle.kts")

    badges = root / "docs/badges"
    badges.mkdir(parents=True, exist_ok=True)
    write_badge(badges, "version", "Version", version[1], "#ff9556")
    write_badge(badges, "downloads", "GitHub downloads", format(args.downloads, ","), "#f7bb84")
    write_badge(badges, "stars", "Stars", format(args.stars, ","), "#ffd48e")


if __name__ == "__main__":
    main()
