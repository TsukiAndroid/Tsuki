#!/usr/bin/env bash
# Renders the Tsuki launcher icon (silver background + crescent moon + 月)
# into legacy WebP densities for pre-Android-O devices and Play Store badges.
# Usage: bash scripts/build_launcher_icon.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RES="$ROOT/app/src/main/res"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Source SVG (silver square w/ rounded corners + crescent + 月)
cat > "$TMP/launcher.svg" <<'SVG'
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 432 432">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#E4E6EA"/>
      <stop offset="1" stop-color="#BFC2C8"/>
    </linearGradient>
    <radialGradient id="halo" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/>
      <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="432" height="432" rx="96" ry="96" fill="url(#bg)"/>
  <circle cx="216" cy="216" r="150" fill="url(#halo)"/>
  <!-- Crescent moon (outer disc minus offset disc) -->
  <path d="M272 112
           A104 104 0 1 0 272 320
           A80 80 0 1 1 272 112 Z"
        fill="#1B1142"/>
  <path d="M256 128
           A88 88 0 1 0 256 304
           A72 72 0 1 1 256 128 Z"
        fill="#3B2A85"/>
  <!-- Kanji 月 (moon), centered, deep indigo -->
  <g fill="#1B1142" font-family="Noto Sans CJK JP, sans-serif" font-weight="700" font-size="172" text-anchor="middle">
    <text x="172" y="282">月</text>
  </g>
</svg>
SVG

# (mdpi=48, hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192) for ic_launcher
declare -A DENSITIES=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )

for d in "${!DENSITIES[@]}"; do
  size="${DENSITIES[$d]}"
  outdir="$RES/mipmap-$d"
  mkdir -p "$outdir"

  # Square launcher
  magick -background none "$TMP/launcher.svg" -resize "${size}x${size}" \
    -quality 92 -define webp:lossless=false "$outdir/ic_launcher.webp"

  # Round launcher: same art, masked to a circle
  magick -background none "$TMP/launcher.svg" -resize "${size}x${size}" \
    \( +clone -alpha extract -threshold 0 -draw "fill black polygon 0,0 0,${size} ${size},${size} ${size},0" \
       -alpha extract -draw "fill white circle $((size/2)),$((size/2)) $((size/2)),0" \) \
    -alpha off -compose CopyOpacity -composite \
    -quality 92 "$outdir/ic_launcher_round.webp"
done

echo "Generated launcher icons in mipmap-* directories."
