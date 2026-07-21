#!/usr/bin/env python3
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
"""
Generate sticker SVG with smooth die-cut outline computed from content boundaries.

Algorithm:
1. Sample boundary points of all visible content elements (circle, text boxes, etc.)
2. For N evenly-spaced angles from center, find the farthest content boundary point (ray-cast)
3. Extend each point 7mm outward along the ray from center
4. Smooth the distance profile (Gaussian-like kernel) to avoid jaggedness
5. Convert the outline to a smooth SVG cubic bezier path via Catmull-Rom spline
"""
import math
import sys
import qrcode

# ===== Configuration =====
NUM_ANGLES = 256          # Number of sample angles (output resolution)
OFFSET_MM = 9.0           # Offset from content boundary in mm
STICKER_HEIGHT_MM = 60.0  # Physical target height in mm
VIEWBOX_HEIGHT = 580.0    # SVG viewBox height units
SMOOTH_WINDOW = 30        # Smoothing kernel half-width (text-only layout needs moderate smoothing)

# Derived
MM_PER_UNIT = STICKER_HEIGHT_MM / VIEWBOX_HEIGHT   # ~0.103 mm per SVG unit
OFFSET_SVG = OFFSET_MM / MM_PER_UNIT               # ~68 SVG units

# Center of all content (text-only layout, no logo circle)
CX, CY = 220.0, 230.0


# ===== Content shape definitions =====

def circle_pts(cx, cy, r, n=180):
    """Sample n points on a circle boundary."""
    return [(cx + r * math.cos(2 * math.pi * i / n),
             cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


def rect_pts(x1, y1, x2, y2, step=2):
    """Sample points along all 4 edges of a rectangle."""
    pts = []
    # horizontal edges
    x = x1
    while x <= x2:
        pts.append((x, y1))
        pts.append((x, y2))
        x += step
    # vertical edges
    y = y1 + step
    while y < y2:
        pts.append((x1, y))
        pts.append((x2, y))
        y += step
    return pts


def rounded_rect_pts(x1, y1, x2, y2, r, step=2):
    """Sample points along a rounded rectangle boundary."""
    pts = []
    # 4 straight edges (excluding corners)
    for x in frange(x1 + r, x2 - r, step):
        pts.append((x, y1))
        pts.append((x, y2))
    for y in frange(y1 + r, y2 - r, step):
        pts.append((x1, y))
        pts.append((x2, y))
    # 4 corner arcs
    corners = [(x2 - r, y1 + r, -math.pi/2, 0),
               (x2 - r, y2 - r, 0, math.pi/2),
               (x1 + r, y2 - r, math.pi/2, math.pi),
               (x1 + r, y1 + r, math.pi, 3*math.pi/2)]
    for ccx, ccy, a0, a1 in corners:
        n_arc = max(4, int(r * (a1 - a0) / step))
        for i in range(n_arc + 1):
            a = a0 + (a1 - a0) * i / n_arc
            pts.append((ccx + r * math.cos(a), ccy + r * math.sin(a)))
    return pts


def frange(start, stop, step):
    """Float range generator."""
    vals = []
    v = start
    while v <= stop + 1e-9:
        vals.append(v)
        v += step
    return vals


# ----- Layout configuration (v5: text-only, no logo circle) -----
# Title "MCP Steroid" + QR symbol on same baseline — hero element
TITLE_BASELINE_Y = 220
TITLE_FONT_SIZE = 52
TITLE_TEXT_WIDTH = 320     # estimated rendered width of "MCP Steroid" bold 52px
QR_BLOCK_SIZE = 38         # cap-height sized symbol (~0.73 * font-size)
QR_GAP = -1                # negative = overlap slightly, like a tight kerned glyph
COMBINED_WIDTH = TITLE_TEXT_WIDTH + QR_GAP + QR_BLOCK_SIZE
TITLE_X = CX - COMBINED_WIDTH / 2
QR_BLOCK_X = TITLE_X + TITLE_TEXT_WIDTH + QR_GAP
QR_BLOCK_Y = TITLE_BASELINE_Y - QR_BLOCK_SIZE + 2  # bottom-aligned with baseline

# Tagline
TAGLINE_Y = 258

# Small "by @jonnyzzz" credit
CREDIT_Y = 282

# QR code
QR_URL = "https://devrig.dev/#qr1"

# ----- Collect all boundary points (v5: text-only) -----
boundary = []

# Title "MCP Steroid" + QR cursor block (treated as one wide element) — hero
boundary.extend(rect_pts(TITLE_X - 4, TITLE_BASELINE_Y - 40,
                         QR_BLOCK_X + QR_BLOCK_SIZE + 4, TITLE_BASELINE_Y + 8, step=2))

# Tagline — monospace 13px, ~42 chars
boundary.extend(rect_pts(70, TAGLINE_Y - 12, 370, TAGLINE_Y + 4, step=2))

# Small credit "by @jonnyzzz" — mono 9px, ~13 chars → ~75px
boundary.extend(rect_pts(182, CREDIT_Y - 7, 258, CREDIT_Y + 3, step=2))

print(f"# Sampled {len(boundary)} boundary points", file=sys.stderr)


# ===== Ray-cast: find max distance at each angle =====

# For each angle bin, track the maximum distance from center
max_dist = [0.0] * NUM_ANGLES
max_point = [None] * NUM_ANGLES

for px, py in boundary:
    dx = px - CX
    dy = py - CY
    dist = math.sqrt(dx * dx + dy * dy)
    angle = math.atan2(dy, dx)
    if angle < 0:
        angle += 2 * math.pi

    bin_idx = int(angle / (2 * math.pi) * NUM_ANGLES) % NUM_ANGLES

    if dist > max_dist[bin_idx]:
        max_dist[bin_idx] = dist
        max_point[bin_idx] = (px, py)

# Fill any empty bins by interpolating from neighbors
def fill_gaps(dist_arr):
    """Fill zero-valued bins by linear interpolation from nearest non-zero neighbors."""
    n = len(dist_arr)
    filled = list(dist_arr)
    for i in range(n):
        if filled[i] > 0:
            continue
        # Search left and right for nearest non-zero
        left_idx, right_idx = None, None
        for d in range(1, n):
            li = (i - d) % n
            if filled[li] > 0 and left_idx is None:
                left_idx = li
                left_dist = d
            ri = (i + d) % n
            if filled[ri] > 0 and right_idx is None:
                right_idx = ri
                right_dist = d
            if left_idx is not None and right_idx is not None:
                break
        if left_idx is not None and right_idx is not None:
            # Linear interpolation
            total = left_dist + right_dist
            filled[i] = (filled[left_idx] * right_dist + filled[right_idx] * left_dist) / total
        elif left_idx is not None:
            filled[i] = filled[left_idx]
        elif right_idx is not None:
            filled[i] = filled[right_idx]
    return filled

max_dist = fill_gaps(max_dist)

empty_count = sum(1 for d in max_dist if d == 0)
print(f"# After fill: {empty_count} empty bins remaining", file=sys.stderr)


# ===== Smooth the distance profile =====

def gaussian_smooth(values, window):
    """Apply circular Gaussian smoothing to a list of values."""
    n = len(values)
    sigma = window / 2.5
    kernel = [math.exp(-0.5 * (i / sigma) ** 2) for i in range(-window, window + 1)]
    ksum = sum(kernel)
    kernel = [k / ksum for k in kernel]

    smoothed = [0.0] * n
    for i in range(n):
        for j, kv in enumerate(kernel):
            idx = (i + j - window) % n
            smoothed[i] += values[idx] * kv
    return smoothed

smoothed_dist = gaussian_smooth(max_dist, SMOOTH_WINDOW)

print(f"# Distance range: {min(smoothed_dist):.1f} - {max(smoothed_dist):.1f} SVG units", file=sys.stderr)
print(f"# Offset: {OFFSET_SVG:.1f} SVG units ({OFFSET_MM}mm)", file=sys.stderr)


# ===== Generate offset outline points =====

outline = []
for i in range(NUM_ANGLES):
    angle = 2 * math.pi * i / NUM_ANGLES
    r = smoothed_dist[i] + OFFSET_SVG
    x = CX + r * math.cos(angle)
    y = CY + r * math.sin(angle)
    outline.append((x, y))


# ===== Convert to smooth SVG path via Catmull-Rom → Cubic Bezier =====

def catmull_rom_to_cubic(p0, p1, p2, p3, tension=1.0/3.0):
    """
    Convert a Catmull-Rom segment p1→p2 into cubic Bezier control points.
    Returns (cp1, cp2) — the two inner control points.
    """
    cp1 = (p1[0] + tension * (p2[0] - p0[0]),
            p1[1] + tension * (p2[1] - p0[1]))
    cp2 = (p2[0] - tension * (p3[0] - p1[0]),
            p2[1] - tension * (p3[1] - p1[1]))
    return cp1, cp2


n = len(outline)
path_parts = [f"M {outline[0][0]:.2f},{outline[0][1]:.2f}"]

for i in range(n):
    p0 = outline[(i - 1) % n]
    p1 = outline[i]
    p2 = outline[(i + 1) % n]
    p3 = outline[(i + 2) % n]
    cp1, cp2 = catmull_rom_to_cubic(p0, p1, p2, p3)
    path_parts.append(
        f"C {cp1[0]:.2f},{cp1[1]:.2f} {cp2[0]:.2f},{cp2[1]:.2f} {p2[0]:.2f},{p2[1]:.2f}"
    )

path_parts.append("Z")
cut_path = "\n           ".join(path_parts)

# ===== Generate inner shape (shrunk inward by white border width) =====
WHITE_BORDER_SVG = 18  # white border width in SVG units (~1.8mm)

inner_outline = []
for i in range(NUM_ANGLES):
    angle = 2 * math.pi * i / NUM_ANGLES
    r = max(0, smoothed_dist[i] + OFFSET_SVG - WHITE_BORDER_SVG)
    x = CX + r * math.cos(angle)
    y = CY + r * math.sin(angle)
    inner_outline.append((x, y))

inner_parts = [f"M {inner_outline[0][0]:.2f},{inner_outline[0][1]:.2f}"]
for i in range(len(inner_outline)):
    p0 = inner_outline[(i - 1) % n]
    p1 = inner_outline[i]
    p2 = inner_outline[(i + 1) % n]
    p3 = inner_outline[(i + 2) % n]
    cp1, cp2 = catmull_rom_to_cubic(p0, p1, p2, p3)
    inner_parts.append(
        f"C {cp1[0]:.2f},{cp1[1]:.2f} {cp2[0]:.2f},{cp2[1]:.2f} {p2[0]:.2f},{p2[1]:.2f}"
    )
inner_parts.append("Z")
inner_path = "\n           ".join(inner_parts)

# Compute viewBox from outline bounds
xs = [p[0] for p in outline]
ys = [p[1] for p in outline]
pad = 5
vb_x = min(xs) - pad
vb_y = min(ys) - pad
vb_w = max(xs) - min(xs) + 2 * pad
vb_h = max(ys) - min(ys) + 2 * pad

print(f"# ViewBox: {vb_x:.0f} {vb_y:.0f} {vb_w:.0f} {vb_h:.0f}", file=sys.stderr)

# ===== Generate QR code SVG path =====

def generate_qr_dark_path(url, x, y, size):
    """Generate SVG path for QR code DARK modules (standard rendering on white block)."""
    qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M,
                        box_size=1, border=1)
    qr.add_data(url)
    qr.make(fit=True)
    matrix = qr.modules
    n = len(matrix)
    cell = size / n
    rects = []
    for row in range(n):
        for col in range(n):
            if matrix[row][col]:  # dark modules
                rx = x + col * cell
                ry = y + row * cell
                rects.append(f"M{rx:.2f},{ry:.2f}h{cell:.2f}v{cell:.2f}h{-cell:.2f}z")
    return " ".join(rects)

qr_dark_path = generate_qr_dark_path(QR_URL, QR_BLOCK_X, QR_BLOCK_Y, QR_BLOCK_SIZE)

# ===== Emit complete SVG =====

svg = f'''<svg xmlns="http://www.w3.org/2000/svg"
     width="{vb_w:.0f}" height="{vb_h:.0f}"
     viewBox="{vb_x:.1f} {vb_y:.1f} {vb_w:.1f} {vb_h:.1f}" fill="none">
  <defs>
    <!-- Background gradient: purple → pink (diagonal) -->
    <linearGradient id="bgGradient" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#7C3AED"/>
      <stop offset="100%" stop-color="#E84D8A"/>
    </linearGradient>
    <clipPath id="stickerClip">
      <path d="{cut_path}"/>
    </clipPath>
  </defs>

  <!-- Die-cut body: white outer -->
  <path d="{cut_path}"
        fill="white" stroke="#E0D8E8" stroke-width="0.5"/>

  <!-- Inner gradient fill -->
  <path d="{inner_path}"
        fill="url(#bgGradient)" stroke="none"/>

  <g clip-path="url(#stickerClip)">
    <!-- Title "MCP Steroid" — WHITE, hero element -->
    <text x="{TITLE_X}" y="{TITLE_BASELINE_Y}" text-anchor="start"
          font-family="-apple-system, BlinkMacSystemFont, \'Segoe UI\', Roboto, Helvetica, Arial, sans-serif"
          font-weight="700" font-size="{TITLE_FONT_SIZE}" fill="white" letter-spacing="-0.5">MCP Steroid</text>

    <!-- QR cursor block: semi-transparent white background for readability -->
    <rect x="{QR_BLOCK_X - 1}" y="{QR_BLOCK_Y - 1}" width="{QR_BLOCK_SIZE + 2}" height="{QR_BLOCK_SIZE + 2}"
          rx="3" fill="white" fill-opacity="0.2"/>
    <rect x="{QR_BLOCK_X}" y="{QR_BLOCK_Y}" width="{QR_BLOCK_SIZE}" height="{QR_BLOCK_SIZE}"
          rx="2" fill="white" fill-opacity="0.92"/>
    <path d="{qr_dark_path}" fill="url(#bgGradient)" fill-opacity="0.85"/>

    <!-- Tagline — white, slightly transparent -->
    <text x="{CX}" y="{TAGLINE_Y}" text-anchor="middle"
          font-family="\'JetBrains Mono\', monospace"
          font-weight="400" font-size="13" fill="white" fill-opacity="0.85">Give AI the whole IDE, not just the files</text>

    <!-- Small credit -->
    <text x="{CX}" y="{CREDIT_Y}" text-anchor="middle"
          font-family="\'JetBrains Mono\', monospace"
          font-weight="400" font-size="9" fill="white" fill-opacity="0.45">by @jonnyzzz</text>
  </g>
</svg>'''

print(svg)
