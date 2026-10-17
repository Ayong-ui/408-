# -*- coding: utf-8 -*-
"""将香炉原型图加工为两张独立透明 PNG 素材：
1. incense-body.png   香炉主体（泛洪填充抠背景，保留炉内浅色材质）
2. incense-shadow.png 落地投影（密度反推 Alpha，柔和渐变）
"""
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage
import os

D = r"D:\Study\简历\generated-images\incense-assets"
BODY_SRC = os.path.join(D, "图像编辑任务_提取香炉主体为游戏UI素材_严格保持原图中三足_2026-10-16T13-25-28.png")
SHADOW_SRC = os.path.join(D, "图像编辑任务_仅提取香炉下方的落地投影为独立素材_保留原图中_2026-10-16T13-25-30.png")

def border_stats(lum, w=30):
    b = np.concatenate([lum[:w].ravel(), lum[-w:].ravel(), lum[:, :w].ravel(), lum[:, -w:].ravel()])
    return np.median(b), b.std()

def ascii_preview(arr, label, chars=" .:-=+*#%@"):
    """arr: 0-255 float/int, 0=空 255=实"""
    small = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).resize((68, 34))
    a = np.array(small)
    print(f"=== {label} (alpha ASCII, 空->实) ===")
    for row in a:
        print("".join(chars[min(9, int(v) * 10 // 256)] for v in row))
    print()

# ---------------- 1. 香炉主体 ----------------
img = Image.open(BODY_SRC).convert("RGB")
rgb = np.array(img).astype(np.float64)
lum = rgb.mean(axis=2)
bg, sigma = border_stats(lum)
print(f"[body] paper bg={bg:.1f} sigma={sigma:.1f}")

# 逐步收紧的纸色阈值测试（从宽到窄，选第一个不泄漏的）
for k in (3.0, 2.6, 2.2, 1.8):
    thresh = bg - k * sigma
    paperish = lum > thresh
    seed = np.zeros_like(paperish)
    seed[0, :] = paperish[0, :]; seed[-1, :] = paperish[-1, :]
    seed[:, 0] = paperish[:, 0]; seed[:, -1] = paperish[:, -1]
    bgmask = ndimage.binary_propagation(seed, mask=paperish)
    obj = ~bgmask
    frac = obj.mean()
    # 连通域分析
    labels, n = ndimage.label(obj)
    sizes = ndimage.sum(obj, labels, range(1, n + 1))
    if n > 0:
        main = int(np.argmax(sizes)) + 1
        main_frac = sizes[main - 1] / obj.size
        n_frag = int((sizes < 2000).sum())
    else:
        main, main_frac, n_frag = 0, 0, 0
    # 内部孔洞（对象区域内被 flood 吃掉的部分）
    holes = ndimage.binary_fill_holes(obj)
    hole_frac = (holes & ~obj).mean()
    print(f"  k={k}: thresh={thresh:.0f} obj={frac*100:.1f}% main={main_frac*100:.1f}% "
          f"frags<2000px={n_frag} holes={hole_frac*100:.2f}%")
    if frac > 0.12 and frac < 0.55 and main_frac > 0.95 * frac:
        break

# 只保留最大连通域（去除纸纹噪点、残留斑点）
labels, n = ndimage.label(obj)
if n > 1:
    sizes = ndimage.sum(obj, labels, range(1, n + 1))
    main = int(np.argmax(sizes)) + 1
    obj = labels == main
    print(f"[body] removed {n-1} fragments, kept main component ({sizes[main-1]:.0f}px)")

# 修复炉口泄漏（香支移除时炉沿背侧被擦掉一段，背景从缺口渗入炉口）：
# 1) 炉口区域内做横向闭运算，封住垂直泄漏通道
ZONE = (slice(405, 550), slice(280, 745))  # y, x 炉口椭圆区域
closed_h = ndimage.binary_closing(obj, structure=np.ones((3, 121), bool))
added = closed_h & ~obj
zone_mask = np.zeros_like(obj); zone_mask[ZONE] = True
added &= zone_mask
obj_before = obj.copy()
obj = obj | added
print(f"[body] horizontal seal: +{int(added.sum())} px in mouth zone")

# 2) 新补全的像素颜色：按行左右邻近不透明像素线性插值 + 墨纹噪声
new_px = np.argwhere(added)
if len(new_px):
    rgb_fix = rgb.copy()
    ys_a, xs_a = new_px[:, 0], new_px[:, 1]
    rng = np.random.default_rng(42)
    for (yy, xx) in zip(ys_a, xs_a):
        row = obj_before[yy]
        left = np.where(row[:xx])[0]
        right = np.where(row[xx + 1:])[0]
        lc = rgb[yy, left[-1]] if len(left) else None
        rc = rgb[yy, xx + 1 + right[0]] if len(right) else None
        if lc is not None and rc is not None:
            t = (xx - left[-1]) / (xx + 1 + right[0] - left[-1])
            c = lc * (1 - t) + rc * t
        elif lc is not None:
            c = lc
        elif rc is not None:
            c = rc
        else:
            c = rgb[yy, xx]
        rgb_fix[yy, xx] = np.clip(c + rng.normal(0, 5, 3), 0, 255)
    # 补全区轻微模糊融合接缝
    blur_zone = np.array(Image.fromarray(np.clip(rgb_fix[ZONE], 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.0)))
    m = added[ZONE]
    zz = rgb_fix[ZONE]
    zz[m] = 0.6 * zz[m] + 0.4 * blur_zone[m]
    rgb_fix[ZONE] = zz
    rgb = rgb_fix
    print(f"[body] inpainted {len(new_px)} px of ash color")

# 3) 重建被擦掉的炉沿背线段：顶部边界大幅上移的列 = 修复过的列，沿线补一道与邻域一致的墨线
y_top_pre = np.full(1024, -1)
y_top_post = np.full(1024, -1)
for xx in range(1024):
    col = np.where(obj_before[:, xx])[0]
    if len(col) and 405 <= col[0] <= 500: y_top_pre[xx] = col[0]
    col2 = np.where(obj[:, xx])[0]
    if len(col2) and 405 <= col2[0] <= 500: y_top_post[xx] = col2[0]
repaired_cols = np.where((y_top_post > 0) & ((y_top_pre < 0) | (y_top_pre - y_top_post > 15)))[0]
# 参考列：未修复、顶部边界正常的列，采样其炉沿墨色
ref_cols = np.where((y_top_pre > 0) & (y_top_pre == y_top_post))[0]
if len(repaired_cols) and len(ref_cols):
    rim_colors = []
    for xx in ref_cols[::5]:
        yt = int(y_top_pre[xx])
        rim_colors.append(rgb[yt:yt + 4, xx].reshape(-1, 3))
    rim_color = np.median(np.concatenate(rim_colors), axis=0)
    rng2 = np.random.default_rng(7)
    for xx in repaired_cols:
        yt = int(y_top_post[xx])
        thick = int(2 + rng2.integers(0, 3))
        for dy in range(-1, thick + 1):
            yv = yt + dy
            if 405 <= yv < 550:
                fade = 1.0 if 0 <= dy < thick else 0.45
                c = rim_color * fade + rgb[yv, xx] * (1 - fade) + rng2.normal(0, 4, 3)
                rgb[yv, xx] = np.clip(c, 0, 255)
    print(f"[body] rebuilt back-rim stroke over {len(repaired_cols)} columns, rim_color={rim_color.round(0)}")

# 羽化边缘：模糊后拉伸，内部保持不透明
alpha = np.where(obj, 255.0, 0.0)
alpha_blur = np.array(Image.fromarray(alpha.astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.3)))
alpha_out = np.clip(alpha_blur.astype(np.float64) * 1.8, 0, 255)

# 边缘去污染：半透明像素的颜色从纸色反推为纯物体色
af = alpha_out / 255.0
band = (af > 0.05) & (af < 0.95)
rgb_out = rgb.copy()
for c in range(3):
    ch = rgb_out[:, :, c]
    unmixed = bg + (ch - bg) / np.maximum(af, 0.05)
    ch[band] = np.clip(unmixed[band], 0, 255)
    # 完全透明区填中性色避免边缘彩边
    ch[af <= 0.05] = 128

body_rgba = np.dstack([rgb_out, alpha_out]).astype(np.uint8)
Image.fromarray(body_rgba, "RGBA").save(os.path.join(D, "incense-body.png"))
ys, xs = np.where(alpha_out > 8)
print(f"[body] saved incense-body.png  content bbox x[{xs.min()}-{xs.max()}] y[{ys.min()}-{ys.max()}] "
      f"opaque={100*(alpha_out>245).mean():.1f}% transparent={100*(alpha_out<10).mean():.1f}%")

# 校验：炉口内香灰区域必须保持不透明（不能被泛洪吃掉）
mouth = alpha_out[440:510, 350:680]
print(f"[body] mouth-interior alpha: mean={mouth.mean():.0f} min={mouth.min():.0f} "
      f"opaque_rate={100*(mouth>200).mean():.1f}%  (应接近 255/100%)")
# 校验：炉足之间的空隙应该透明
gap = alpha_out[880:925, 300:360]
print(f"[body] feet-gap alpha: mean={gap.mean():.0f} (应接近 0)")
ascii_preview(alpha_out, "incense-body alpha")

# ---------------- 2. 落地投影 ----------------
img2 = Image.open(SHADOW_SRC).convert("RGB")
rgb2 = np.array(img2).astype(np.float64)
lum2 = rgb2.mean(axis=2)
bg2, sigma2 = border_stats(lum2)
print(f"[shadow] paper bg={bg2:.1f} sigma={sigma2:.1f}")

# 影子核心 = 最深的大块连通域
core_seed = lum2 < bg2 - 2.0 * sigma2
labels2, n2 = ndimage.label(core_seed)
if n2 > 1:
    sizes2 = ndimage.sum(core_seed, labels2, range(1, n2 + 1))
    order = np.argsort(sizes2)[::-1]
    core = np.zeros_like(core_seed)
    kept = 0
    for i in order:
        if sizes2[i] > 3000:  # 只保留大块（可能左右脚两团）
            core |= labels2 == (i + 1)
            kept += 1
    print(f"[shadow] {n2} blobs, kept {kept} large (>=3000px)")
else:
    core = core_seed

# ROI：核心外扩 45px，之后 ROI 外一律透明（去掉纸纹噪点）
roi = ndimage.binary_dilation(core, iterations=45)

# Alpha 密度：从噪声地板到最深浓度线性映射
noise_floor = bg2 - 2.2 * sigma2
dense_min = np.percentile(lum2[core], 5) if core.any() else bg2 - 100
print(f"[shadow] noise_floor={noise_floor:.0f} dense_min={dense_min:.0f}")
alpha_s = np.clip((noise_floor - lum2) / max(noise_floor - dense_min, 1), 0, 1)
alpha_s[~roi] = 0
# 轻微平滑去颗粒
alpha_s = np.array(Image.fromarray((alpha_s * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))) / 255.0
alpha_s[~roi] = 0
alpha_s = np.clip(alpha_s * 1.05, 0, 1)

# 墨色：从高密度像素反推纯墨颜色（对纸色去乘）
strong = alpha_s > 0.45
if strong.sum() > 100:
    ink = np.array([
        np.median(bg2 + (rgb2[:, :, c][strong] - bg2) / alpha_s[strong]) for c in range(3)
    ])
    ink = np.clip(ink, 30, 200)
else:
    ink = np.array([100.0, 100.0, 100.0])
print(f"[shadow] ink color = {ink.round(1)}")

rgb_final = np.zeros((*alpha_s.shape, 3))
for c in range(3):
    rgb_final[:, :, c] = ink[c]

shadow_rgba = np.dstack([rgb_final, alpha_s * 255]).astype(np.uint8)
Image.fromarray(shadow_rgba, "RGBA").save(os.path.join(D, "incense-shadow.png"))
ys2, xs2 = np.where(alpha_s > 0.02)
print(f"[shadow] saved incense-shadow.png  content bbox x[{xs2.min()}-{xs2.max()}] y[{ys2.min()}-{ys2.max()}] "
      f"max_alpha={alpha_s.max():.2f} mean_alpha_in_bbox={alpha_s[ys2.min():ys2.max(), xs2.min():xs2.max()].mean():.3f}")
ascii_preview(alpha_s * 255, "incense-shadow alpha")
print("DONE")
