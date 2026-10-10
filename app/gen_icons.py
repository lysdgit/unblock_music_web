#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""无第三方依赖的启动图标生成器（纯 zlib + struct 手写 PNG）。

生成传统（API < 26）用的 ic_launcher.png / ic_launcher_round.png。
设计与 res/drawable/ic_launcher_background.xml、ic_launcher_foreground.xml 保持一致：
蓝色渐变底 + 白色音符。

用法：python gen_icons.py
"""
import os
import struct
import zlib

DESIGN = 108.0          # 设计坐标系边长（与自适应图标一致）
SCALE, TX, TY = 4.4, 1.2, 1.2   # 音符在 108 设计格中的摆放
C_TOP = (0x4F, 0x8C, 0xFF)      # 渐变起点
C_BOT = (0x2A, 0x56, 0xC6)      # 渐变终点
WHITE = (0xFF, 0xFF, 0xFF)
SS = 4                          # 超采样倍数（抗锯齿）


def write_png(path, width, height, rows):
    """rows: 每行是 bytes，长度 = width * 4 (RGBA)"""
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    blob = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    with open(path, "wb") as fh:
        fh.write(blob)


def in_rounded_rect(x, y, size, radius):
    if x < 0 or y < 0 or x > size or y > size:
        return False
    r = radius
    cx = min(max(x, r), size - r)
    cy = min(max(y, r), size - r)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= r * r


def in_glyph(x, y):
    """把设计坐标换算回 24x24 音符字形坐标，判断是否落在音符内部。"""
    gx = (x - TX) / SCALE
    gy = (y - TY) / SCALE
    if (gx - 10.0) ** 2 + (gy - 17.0) ** 2 <= 16.0:      # 符头圆
        return True
    if 12.0 <= gx <= 14.0 and 3.0 <= gy <= 17.0:          # 符干
        return True
    if 12.0 <= gx <= 18.0 and 3.0 <= gy <= 7.0:           # 符尾旗
        return True
    return False


def mix(c1, c2, t):
    return tuple(int(round(a + (b - a) * t)) for a, b in zip(c1, c2))


def render(size, round_icon):
    radius = DESIGN * 0.5 if round_icon else DESIGN * 0.20
    step = DESIGN / size
    rows = []

    for py in range(size):
        row = bytearray()
        for px in range(size):
            r_sum = g_sum = b_sum = 0
            valid = 0
            for sy in range(SS):
                for sx in range(SS):
                    dx = (px + (sx + 0.5) / SS) * step
                    dy = (py + (sy + 0.5) / SS) * step

                    if round_icon:
                        ex, ey = dx - 54.0, dy - 54.0
                        if ex * ex + ey * ey > 54.0 * 54.0:
                            continue
                    elif not in_rounded_rect(dx, dy, DESIGN, radius):
                        continue

                    valid += 1
                    if in_glyph(dx, dy):
                        cr, cg, cb = WHITE
                    else:
                        t = (dx + dy) / (2.0 * DESIGN)
                        cr, cg, cb = mix(C_TOP, C_BOT, t)
                    r_sum += cr
                    g_sum += cg
                    b_sum += cb

            total = SS * SS
            if valid == 0:
                row += b"\x00\x00\x00\x00"
            else:
                alpha = (255 * valid) // total
                row += bytes((r_sum // valid, g_sum // valid, b_sum // valid, alpha))
        rows.append(bytes(row))
    return rows


def main():
    app_dir = os.path.dirname(os.path.abspath(__file__))
    res_dir = os.path.join(app_dir, "src", "main", "res")

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

    for name, px in densities.items():
        out_dir = os.path.join(res_dir, "mipmap-" + name)
        os.makedirs(out_dir, exist_ok=True)
        write_png(os.path.join(out_dir, "ic_launcher.png"), px, px, render(px, False))
        write_png(os.path.join(out_dir, "ic_launcher_round.png"), px, px, render(px, True))
        print("generated mipmap-%-9s %dpx" % (name, px))


if __name__ == "__main__":
    main()
