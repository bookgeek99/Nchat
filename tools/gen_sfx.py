#!/usr/bin/env python3
"""合成 Nchat 的操作音效（不依赖任何外部素材）。

两个音效：
  tick.wav   —— 处理单条通知：像「水滴落进水面」的清脆短音 + 轻微回弹
  finish.wav —— 全部处理完：三音上行琶音，收尾感

设计要点：
  - 时长极短（tick 约 90ms），连点几十次也不会拖沓
  - 主频 1400~1900Hz，手机小喇叭上辨识度高但不刺耳
  - 带快速衰减包络（指数衰减），符合"清脆"的听感
  - 加一层轻微二次谐波，避免听上去像纯电子蜂鸣
"""
import math, struct, wave, os, random

SR = 44100

def env_exp(n, total, rate=28.0):
    """指数衰减包络"""
    return math.exp(-rate * n / total)

def tone(freq, dur, amp=0.5, decay=28.0, harmonics=((2, 0.18),)):
    n_total = int(SR * dur)
    out = []
    for i in range(n_total):
        t = i / SR
        v = math.sin(2 * math.pi * freq * t)
        for h, ha in harmonics:
            v += ha * math.sin(2 * math.pi * freq * h * t)
        v *= env_exp(i, n_total, decay) * amp
        out.append(v)
    return out

def mix(*tracks):
    n = max(len(t) for t in tracks)
    buf = [0.0] * n
    for t in tracks:
        for i, v in enumerate(t):
            buf[i] += v
    return buf

def silence(dur):
    return [0.0] * int(SR * dur)

def write_wav(path, samples, max_amp=0.85):
    peak = max(1e-9, max(abs(x) for x in samples))
    scale = max_amp / peak
    data = b''.join(
        struct.pack('<h', max(-32767, min(32767, int(x * scale * 32767))))
        for x in samples)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data)
    print(f'  {os.path.basename(path)}  {len(samples)/SR*1000:.0f}ms  {os.path.getsize(path)}B')


def make_tick():
    """
    处理单条通知的音效 = 水滴 + 一点点点按感。

    水滴的做法：一个快速下滑的短音（频率从高往下扫），
    这种"pui"的下坠感就是水滴声的核心。
    """
    dur = 0.09
    n = int(SR * dur)
    out = []
    f0, f1 = 1750.0, 1150.0     # 下滑
    for i in range(n):
        t = i / SR
        p = i / n
        f = f0 + (f1 - f0) * (p ** 0.55)
        v = math.sin(2 * math.pi * f * t)
        v += 0.22 * math.sin(2 * math.pi * f * 2.02 * t)   # 轻微谐波
        # 前 6ms 做一个极短的 attack，避免爆音
        atk = min(1.0, i / (SR * 0.006))
        out.append(v * atk * env_exp(i, n, 34.0) * 0.6)
    return out


def make_finish():
    """
    全部处理完 = 三个上行音 + 尾音，有种"叮—叮—叮↗"的完成感。
    C6 / E6 / G6，最后一个延长并加一点混响感。
    """
    notes = [1046.5, 1318.5, 1568.0]
    parts = []
    gap = 0.055
    for idx, f in enumerate(notes):
        dur = 0.16 if idx == len(notes) - 1 else 0.11
        parts.append(tone(f, dur, amp=0.42, decay=18.0,
                          harmonics=((2, 0.16), (3, 0.06))))
        if idx < len(notes) - 1:
            parts.append(silence(gap))
    body = []
    for p in parts:
        body.extend(p)
    # 尾巴：最后一音的衰减延长版，营造空间感
    tail = tone(notes[-1] * 2, 0.22, amp=0.10, decay=12.0, harmonics=())
    return mix(body, silence(0.02) + tail[:len(body) - int(SR*0.02)])


if __name__ == '__main__':
    out_dir = '/root/projects/nchat/android/app/src/main/res/raw'
    os.makedirs(out_dir, exist_ok=True)
    print('生成音效：')
    write_wav(os.path.join(out_dir, 'tick.wav'), make_tick())
    write_wav(os.path.join(out_dir, 'finish.wav'), make_finish())
    print('完成')