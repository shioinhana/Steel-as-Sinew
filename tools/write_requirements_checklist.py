#!/usr/bin/env python3
"""动作模组清单：四向走跑、跳两段、太刀衔接、双刀侧移、长矛。外形留给美术。"""
from pathlib import Path
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation
from openpyxl.chart.shapes import GraphicalProperties  # noqa: keep import quiet if unused

OUT = Path(__file__).resolve().parent.parent / "docs" / "动作战斗_需求对照清单.xlsx"
COPY = Path("/Users/mac/Downloads/新Minecraft 1.21.1 动作战斗模组需求") / "动作战斗_需求对照清单.xlsx"

HEAD = [
    "完成", "优先级", "武器套", "模组", "Clip 名（文件里就叫这个）",
    "层", "人朝哪", "脚往哪", "Loop", "建议时长",
    "可以从哪些动作进来", "可以接到哪些动作", "现状", "美术说明（外形自己定，这里只定接口）",
]

HEAD_FILL = PatternFill("solid", fgColor="1F4E79")
HEAD_FONT = Font(color="FFFFFF", bold=True, name="Microsoft YaHei", size=10)
CELL = Font(name="Microsoft YaHei", size=9)
WRAP = Alignment(wrap_text=True, vertical="center")
THIN = Border(
    left=Side(style="thin", color="B0B0B0"),
    right=Side(style="thin", color="B0B0B0"),
    top=Side(style="thin", color="B0B0B0"),
    bottom=Side(style="thin", color="B0B0B0"),
)
PRI_FILL = {
    "P0 先做": PatternFill("solid", fgColor="C00000"),
    "P1 接着做": PatternFill("solid", fgColor="ED7D31"),
    "P2 预留": PatternFill("solid", fgColor="5B9BD5"),
}
SET_FILL = {
    "下半身-太刀": PatternFill("solid", fgColor="FFF2CC"),
    "下半身-双刀": PatternFill("solid", fgColor="FCE4D6"),
    "下半身-长矛": PatternFill("solid", fgColor="E2F0D9"),
    "太刀-上半身": PatternFill("solid", fgColor="DDEBF7"),
    "双刀-上半身": PatternFill("solid", fgColor="D0CECE"),
    "长矛-上半身": PatternFill("solid", fgColor="C6EFCE"),
    "太刀-全身": PatternFill("solid", fgColor="BDD7EE"),
    "双刀-全身": PatternFill("solid", fgColor="D9D9D9"),
    "长矛-全身": PatternFill("solid", fgColor="C6E0B4"),
    "FP太刀": PatternFill("solid", fgColor="DDEBF7"),
    "FP双刀": PatternFill("solid", fgColor="F8CBAD"),
    "FP长矛": PatternFill("solid", fgColor="E2F0D9"),
}
NOW_FILL = {
    "只有前进": PatternFill("solid", fgColor="FFC7CE"),
    "缺失": PatternFill("solid", fgColor="FFC7CE"),
    "有占位": PatternFill("solid", fgColor="FFEB9C"),
    "有片可精修": PatternFill("solid", fgColor="C6EFCE"),
    "程序还没接这个名字": PatternFill("solid", fgColor="BDD7EE"),
}

U = "上半身（臂、头、upper_body；不要动腿）"
LO = "下半身（腿、髋、root 位移）"
FB = "全身（会盖住走路）"
FP = "第一人称整套手臂"

YES = "☐"


def row(pri, wset, mod, clip, layer, face, feet, loop, dur, inn, out, now, note):
    return [YES, pri, wset, mod, clip, layer, face, feet, loop, dur, inn, out, now, note]


ROWS = []

# =====================================================================
# 下半身：面向镜头的四向走跑 + 跳跃两段
# 你现在只有前进。后退/侧移 = 人仍朝镜头，脚往反方向或侧面迈。
# =====================================================================
S = "下半身-太刀"
# 太刀持刀姿态下的下半身（单手太刀）
ROWS += [
    row("P0 先做", S, "战斗待机", "idle", LO, "朝镜头", "站住", "true", "1.0–2.0s 循环",
        "落地、收招、松键", "走/跑/跳/砍/挡/闪/拔刀的反方向是收刀",
        "有片可精修", "现名 static_idle。持太刀站桩，腿可轻微重心。手臂可随便，会被上半身砍盖掉。"),
    row("P0 先做", S, "向前走", "walk_f", LO, "朝镜头", "向前", "true", "一步约 0.8–1.0s",
        "待机、止跑、落地、砍的后摇", "walk_b/l/r、run_f、跳、砍、挡、闪",
        "只有前进", "现 walk_hold 是「身体转向移动方向的前进」。这片要改成：脸仍朝镜头，脚向前。有前进片就扩成这个名字。"),
    row("P0 先做", S, "向前看，向后退", "walk_b", LO, "朝镜头", "向后", "true", "与 walk_f 同拍",
        "待机、其他走、砍后摇", "其他走/跑/跳/砍/挡/闪",
        "缺失", "背步。上身别大扭，扭了会和砍 overlay 打架。步幅比前走略小。"),
    row("P0 先做", S, "向前看，左侧移", "walk_l", LO, "朝镜头", "向左", "true", "同拍",
        "同上", "同上", "缺失", "侧步。太刀可略收刀身，避免刃穿腿。"),
    row("P0 先做", S, "向前看，右侧移", "walk_r", LO, "朝镜头", "向右", "true", "同拍",
        "同上", "同上", "缺失", ""),
    row("P1 接着做", S, "前左斜走", "walk_fl", LO, "朝镜头", "前左", "true", "同拍",
        "四向走之间", "四向走/跑/跳/砍", "缺失", "没有就程序混 walk_f+walk_l，有独立片更好。"),
    row("P1 接着做", S, "前右斜走", "walk_fr", LO, "朝镜头", "前右", "true", "同拍", "同上", "同上", "缺失", ""),
    row("P1 接着做", S, "后左斜走", "walk_bl", LO, "朝镜头", "后左", "true", "同拍", "同上", "同上", "缺失", ""),
    row("P1 接着做", S, "后右斜走", "walk_br", LO, "朝镜头", "后右", "true", "同拍", "同上", "同上", "缺失", ""),
    row("P0 先做", S, "向前跑", "run_f", LO, "朝镜头", "向前", "true", "一步约 0.5–0.7s",
        "走加速、落地", "跑四向、跳、砍、闪",
        "只有前进", "现 run_hold。改成脸朝镜头的前跑，或保持身体前倾但头仍尽量朝镜头。"),
    row("P0 先做", S, "向前看，向后跑/快退", "run_b", LO, "朝镜头", "向后", "true", "略慢于 run_f",
        "走/跑", "跑四向、跳、砍、闪", "缺失", "可以是快速背步，不必真的倒着冲刺。"),
    row("P0 先做", S, "向前看，左侧跑", "run_l", LO, "朝镜头", "向左", "true", "同 run_f 拍",
        "走/跑", "同上", "缺失", ""),
    row("P0 先做", S, "向前看，右侧跑", "run_r", LO, "朝镜头", "向右", "true", "同拍",
        "走/跑", "同上", "缺失", ""),
    row("P0 先做", S, "起跳（上升段）", "jump_up", LO, "朝镜头", "离地向上", "false，停在最高点姿势",
        "0.25–0.45s", "走/跑/待机", "jump_down；空中也可接砍/闪（程序可关）",
        "有占位", "现名 jump。只做蹬地到最高点。脚离地。不要做整段跳下落。"),
    row("P0 先做", S, "下落段", "jump_down", LO, "朝镜头", "向下", "true 循环到落地",
        "0.4–0.8s 一圈", "jump_up 顶点、走跑中掉下去", "land",
        "有占位", "现名 fall。滞空姿势循环，等落地切 land。"),
    row("P0 先做", S, "落地缓冲", "land", LO, "朝镜头", "站住或接走", "false",
        "0.15–0.30s", "jump_down", "idle / walk_* / 砍 / 闪",
        "缺失", "屈膝缓冲。没有这片会从下落直接弹回待机。"),
    row("P1 接着做", S, "蹲守", "crouch_idle", LO, "朝镜头", "蹲住", "true", "1s",
        "按 C", "起身回 idle/走；暂不要求蹲走", "有占位", "现程序压腿。"),
    row("P1 接着做", S, "前走起步/止步（可选）", "walk_f_start / walk_f_end", LO, "朝镜头", "向前", "false",
        "0.3–0.6s", "idle↔walk_f", "walk_f / idle",
        "有占位", "现 walk_start/end 只服务前进。四向不必各做 start/end，程序可淡入 hold。"),
]

S = "下半身-双刀"
ROWS += [
    row("P0 先做", S, "双刀待机", "idle", LO, "朝镜头", "站住", "true", "1–2s",
        "收招、落地", "四向走/跑/跳/砍", "有片可精修", "双手持刀站桩。和太刀不是同一片，在 doublesword 文件里。"),
    row("P0 先做", S, "双刀前走", "walk_f", LO, "朝镜头", "向前", "true", "同太刀拍子更好混",
        "待机", "走四向/跑/砍", "只有前进", "持双刀的前进。"),
    row("P0 先做", S, "向前看，双刀后退", "walk_b", LO, "朝镜头", "向后", "true", "同拍",
        "待机/走", "走/跑/砍/闪", "缺失", ""),
    row("P0 先做", S, "向前看，双刀左侧身移动", "walk_l", LO, "朝镜头", "向左", "true", "同拍",
        "待机/走", "走/跑/砍/闪", "缺失", "侧身、双刀拉开或收在身侧，避免交叉穿模。这是你点名要的片。"),
    row("P0 先做", S, "向前看，双刀右侧身移动", "walk_r", LO, "朝镜头", "向右", "true", "同拍",
        "待机/走", "走/跑/砍/闪", "缺失", "左右侧移可以不对称（有刀那一侧步幅不同）。"),
    row("P1 接着做", S, "双刀四向斜走", "walk_fl/fr/bl/br", LO, "朝镜头", "斜向", "true", "同拍",
        "四向走", "走/跑/砍", "缺失", "可选，程序能混。"),
    row("P0 先做", S, "双刀前跑", "run_f", LO, "朝镜头", "向前", "true", "快于走",
        "走加速", "跑四向/跳/砍/闪", "只有前进", "现 run_hold。"),
    row("P0 先做", S, "双刀后/左/右跑", "run_b / run_l / run_r", LO, "朝镜头", "后/左/右", "true", "同拍",
        "走/跑", "跑/跳/砍/闪", "缺失", "侧跑可做成持刀交叉步。"),
    row("P0 先做", S, "双刀起跳 / 下落 / 落地", "jump_up / jump_down / land", LO, "朝镜头", "上/下/缓冲",
        "false / true / false", "同太刀", "走跑", "下落↔落地↔走", "有占位", "跳两段+落地，持双刀。现 jump/fall 占位。"),
    row("P1 接着做", S, "双刀蹲", "crouch_idle", LO, "朝镜头", "蹲", "true", "1s", "C", "idle", "有占位", ""),
]

S = "下半身-长矛"
ROWS += [
    row("P1 接着做", S, "持矛待机", "idle", LO, "朝镜头", "站住", "true", "1–2s",
        "收招、落地", "走/跑/跳/刺/挡", "缺失", "矛尖朝前或斜上，整套在新文件 third_person_spear。"),
    row("P1 接着做", S, "持矛前走", "walk_f", LO, "朝镜头", "向前", "true", "可略慢于太刀",
        "待机", "走四向/跑/刺", "缺失", "矛要避免戳地。"),
    row("P1 接着做", S, "向前看，持矛后退", "walk_b", LO, "朝镜头", "向后", "true", "同拍",
        "走", "走/跑/刺/挡", "缺失", "背步，矛仍朝镜头前方（威胁方向）。"),
    row("P1 接着做", S, "向前看，持矛左/右侧移", "walk_l / walk_r", LO, "朝镜头", "左/右", "true", "同拍",
        "走", "走/跑/刺", "缺失", "侧移时矛可横在身前。"),
    row("P2 预留", S, "持矛斜走四向", "walk_fl/fr/bl/br", LO, "朝镜头", "斜", "true", "同拍", "走", "走/刺", "缺失", "可选"),
    row("P1 接着做", S, "持矛四向跑", "run_f / run_b / run_l / run_r", LO, "朝镜头", "对应方向", "true", "快",
        "走加速", "跑/跳/刺/闪", "缺失", "前跑可收矛，避免超长穿模。"),
    row("P1 接着做", S, "持矛起跳 / 下落 / 落地", "jump_up / jump_down / land", LO, "朝镜头", "上/下/缓冲",
        "false/true/false", "同太刀", "走跑", "下落→落地→走", "缺失", "两段跳+落地。"),
    row("P2 预留", S, "持矛蹲", "crouch_idle", LO, "朝镜头", "蹲", "true", "1s", "C", "idle", "缺失", "矛怎么办由美术定。"),
]

# =====================================================================
# 太刀上半身：可衔接模组。招式外形留给美术，这里只定「哪一招、谁接谁」。
# 左键走左路，右键走右路，左右键能互相插；双手砍只从完美格挡进。
# =====================================================================
S = "太刀-上半身"
# 衔接约定写在 进来/出去 列
L_NEXT = "左手左砍 / 右手左砍 / 右手右砍 / 格挡 / 闪 / 走跑 / 跳"
R_NEXT = "右手右砍 / 左手右砍 / 左手左砍 / 格挡 / 闪 / 走跑 / 跳"

ROWS += [
    row("P0 先做", S, "左手左砍（左路起手）", "attack1", U, "朝镜头/朝目标", "由下半身决定", "false",
        "0.7–1.0s（现 0.9）。出刀约总长 30%–45% 处",
        "待机、任意走跑、落地、上一刀后摇可取消点、闪后、挡收回",
        "右手左砍(attack3)、右手右砍(attack4)、挡、闪、走、跳",
        "有片可精修",
        "太刀从左往右或斜斩，左手持刀。只 K 上半身。标出刀开始/结束秒。后摇前半可被下一刀取消。"),
    row("P0 先做", S, "右手左砍（左路第2刀）", "attack3", U, "朝镜头/朝目标", "下半身继续走", "false",
        "1.2–1.6s（现 1.6，出刀偏晚，请提前）",
        "左手左砍的取消点、待机强打左键第二下",
        "右手右砍(attack4)、预留左路第3刀、挡、闪、走",
        "有片可精修",
        "左手刀交到右手再往左路砍，或右手补一刀左向。必须是右手在发力。可接右路，形成左右交替。"),
    row("P0 先做", S, "右手右砍（右路起手）", "attack4", U, "朝镜头/朝目标", "下半身继续走", "false",
        "1.2–1.6s（现 1.53，出刀太晚）",
        "待机右键、任意走跑、左手左砍取消点（左右交替）",
        "左手右砍(attack2)、左手左砍(attack1)、挡、闪、走",
        "有片可精修",
        "右键第一刀。太刀右起手。出刀帧提到刀刃挥到身前。"),
    row("P0 先做", S, "左手右砍（右路第2刀）", "attack2", U, "朝镜头/朝目标", "下半身继续走", "false",
        "0.9–1.2s（现 1.1）",
        "右手右砍的取消点",
        "左手左砍(attack1)、预留右路第3刀、挡、闪、走",
        "有片可精修",
        "右路补刀，左手发力往右。接回 attack1 就能循环左右。"),
    row("P0 先做", S, "双手砍（弹刀/完美格挡奖励）", "attack_dual", U, "朝目标", "下半身可走可站", "false",
        "0.8–1.2s（现 0.9 混骨）",
        "只从完美格挡成功、或 parry_success 结束",
        "走、闪、挡，不再接普通第2刀（避免超长连）",
        "有占位",
        "双手或双手换握的一记重斩。独立手 K，不要把左右砍拼在一起。"),
    row("P1 接着做", S, "左路第3刀（预留衔接）", "attack5", U, "朝目标", "下半身", "false",
        "1.2–1.8s", "右手左砍(attack3) 的取消点", "双手砍或收招回走",
        "程序还没接这个名字",
        "给美术多一节连段用。程序目前每边只播 2 刀，接上后左键可 1-3。外形自定（例如纵斩收刀）。"),
    row("P1 接着做", S, "右路第3刀（预留衔接）", "attack6", U, "朝目标", "下半身", "false",
        "1.2–1.8s", "左手右砍(attack2) 的取消点", "双手砍或收招回走",
        "程序还没接这个名字",
        "右键第三刀。和 attack5 对称为一套。"),
    row("P0 先做", S, "格挡持有", "defend", U, "朝镜头", "下半身可走（顶盾走）", "停在最后一帧",
        "进入 0.1–0.2s，然后循环最后一帧",
        "待机、走、任意刀的取消点、落地",
        "defend_end；完美则 parry_success；被打硬直则 hurt",
        "有片可精修",
        "太刀横挡或刀背挡。上半身。腿继续走。"),
    row("P0 先做", S, "格挡收回", "defend_end", U, "朝镜头", "下半身", "false",
        "0.2–0.35s", "松开格挡", "idle/走/立刻再砍或再挡",
        "有占位", "从挡收回持刀。现 0.22s 生成，要手 K。"),
    row("P1 接着做", S, "完美格挡成功", "parry_success", U, "朝目标", "下半身可顿一下", "false",
        "0.3–0.5s", "格挡窗口内挡住", "attack_dual（奖励刀）或走/闪",
        "缺失", "弹开的一下。结束可自动接双手砍。"),
    row("P1 接着做", S, "蓄力持有", "aiming", U, "朝目标", "可慢走", "停最后一帧",
        "进入 0.2s+", "按住攻击超过阈值", "charge_release 或松手变普通起手",
        "有片可精修", "太刀举刀蓄。"),
    row("P1 接着做", S, "蓄力放出", "charge_release", U, "朝目标", "下半身", "false",
        "0.8–1.4s，独立出刀帧", "蓄力满松手", "走/闪/挡",
        "程序还没接这个名字", "一记重斩。不要复用 attack1。"),
    row("P1 接着做", S, "检视", "inspect", U, "看刀", "站住（程序会禁走）", "true",
        "循环", "空闲按 V", "被走/砍/挡/闪/受伤打断回 idle",
        "有片可精修", "看刀。"),
    row("P1 接着做", S, "上半身挨打", "hurt", U, "被打方向", "下半身可被硬直停", "false",
        "0.25–0.4s", "被命中", "idle/走", "有占位", "肩一沉即可。"),
]

S = "太刀-全身"
ROWS += [
    row("P0 先做", S, "拔刀", "draw", FB, "朝镜头", "可小步", "false",
        "0.5–0.9s", "收刀待机按 R", "idle/走；播完才允许砍（程序要卡死）",
        "有片可精修", "刀从背到手。标「刀入手」那一帧给程序切模型。"),
    row("P0 先做", S, "收刀", "put_away", FB, "朝镜头", "站住", "false",
        "0.5–0.9s", "战斗中按 R，且不在砍中（或可被闪取消，策划定）", "非战斗待机",
        "有片可精修", "刀到手到背。"),
    row("P0 先做", S, "前闪", "dodge_f", FB, "朝镜头", "向前滚/垫步", "false",
        "0.25–0.40s，和位移同长", "走跑、刀的后摇（若允许取消）", "idle/走/砍",
        "缺失", "现误用 run_start。翻滚或垫步自定，但落地要稳。"),
    row("P0 先做", S, "后闪", "dodge_b", FB, "朝镜头", "向后", "false", "同上", "同上", "同上", "缺失", "无方向闪避也播这个。"),
    row("P0 先做", S, "左闪", "dodge_l", FB, "朝镜头", "向左", "false", "同上", "同上", "同上", "缺失", ""),
    row("P0 先做", S, "右闪", "dodge_r", FB, "朝镜头", "向右", "false", "同上", "同上", "同上", "缺失", ""),
    row("P2 预留", S, "太刀特殊（突进斩）", "special", FB, "朝目标", "向前冲", "false",
        "0.6–1.2s", "特殊键", "走/砍起手", "缺失", "原文 sword_dash_attack。外形自定。"),
]

# =====================================================================
# 双刀上半身 + 全身
# =====================================================================
S = "双刀-上半身"
ROWS += [
    row("P0 先做", S, "左手左砍（左路起手）", "attack_1", U, "朝镜头", "下半身可侧移", "false",
        "1.2–1.6s（现 1.6）", "待机/走/侧移", "右手左砍(attack2)、右路起手(attack3)、挡、闪",
        "有片可精修", "注意：双刀这招叫 attack_1 不是 attack1。侧移走下半身 walk_l/r。"),
    row("P0 先做", S, "右手左砍（左路第2）", "attack2", U, "朝镜头", "下半身", "false",
        "1.2–1.6s；现出刀从 0s 开始，必须延后", "attack_1 取消点", "右路、挡、闪、走",
        "有片可精修", "双手都在，这刀右手发力。"),
    row("P0 先做", S, "右手右砍（右路起手）", "attack3", U, "朝镜头", "下半身", "false",
        "1.2–1.6s", "待机右键、左路取消点", "左手右砍(attack4)、左路、挡、闪",
        "有片可精修", "双刀右键第一刀是 attack3（和太刀右键第一刀 attack4 不同）。"),
    row("P0 先做", S, "左手右砍（右路第2）", "attack4", U, "朝镜头", "下半身", "false",
        "1.2–1.6s", "attack3 取消点", "左路起手、挡、闪、走",
        "有片可精修", ""),
    row("P0 先做", S, "双手砍", "attack_dual", U, "朝目标", "下半身", "false",
        "1.2–1.8s（现 1.6 混骨）", "完美格挡后", "走/侧移/闪", "有占位", "双刀交叉或同时斩，独立手 K。"),
    row("P0 先做", S, "格挡 / 收回", "defence_1 / defend_end", U, "朝镜头", "可侧移", "hold / false",
        "收回 0.2–0.35s", "X 按下/松开", "砍、走、闪", "有占位", "源片 defence_1。收回要手 K。"),
    row("P1 接着做", S, "完美格挡 / 蓄力 / 检视 / 挨打", "parry_success / aiming / inspect / hurt", U,
        "朝目标", "下半身", "false 或 hold", "同太刀", "同太刀规则", "同太刀",
        "有占位", "双刀各做一套，不要复用太刀文件。"),
]
S = "双刀-全身"
ROWS += [
    row("P0 先做", S, "拔刀 / 收刀", "draw / put_away", FB, "朝镜头", "小步", "false", "0.6–1.3s",
        "R", "idle", "有片可精修", "两把刀从背到双手。"),
    row("P0 先做", S, "四向闪避", "dodge_f/b/l/r", FB, "朝镜头", "对应方向", "false", "0.25–0.40s",
        "走/侧移/后摇", "idle/走", "缺失", "持双刀翻滚或垫步。"),
    row("P2 预留", S, "双刀特殊", "special", FB, "朝目标", "前冲", "false", "0.6–1.2s", "特殊键", "走", "缺失", ""),
]

# =====================================================================
# 长矛：刺击模组，同样左右路可衔接
# =====================================================================
S = "长矛-上半身"
ROWS += [
    row("P1 接着做", S, "左刺起手", "spear_l1", U, "朝目标", "下半身可前走/后退", "false",
        "0.6–0.9s，出刀短而靠前（突刺）",
        "待机、四向走、落地",
        "左刺第2(spear_l2)、右刺起手(spear_r1)、挡、闪、走",
        "缺失",
        "左手或主手向前刺。判定是一条窄长胶囊，不是横扫。外形自定。"),
    row("P1 接着做", S, "左刺第2（连刺）", "spear_l2", U, "朝目标", "下半身", "false",
        "0.6–1.0s", "spear_l1 取消点", "右刺、预留 spear_l3、挡、闪",
        "缺失", "第二次前刺或上挑，仍是刺不是砍。可接右路形成左右交替。"),
    row("P1 接着做", S, "右刺起手", "spear_r1", U, "朝目标", "下半身", "false",
        "0.6–0.9s", "待机右键、左刺取消点", "右刺第2、左刺、挡、闪",
        "缺失", "右键第一刺。可与左刺交替。"),
    row("P1 接着做", S, "右刺第2", "spear_r2", U, "朝目标", "下半身", "false",
        "0.6–1.0s", "spear_r1 取消点", "左刺起手、预留 spear_r3、挡、闪",
        "缺失", ""),
    row("P2 预留", S, "左/右第3刺", "spear_l3 / spear_r3", U, "朝目标", "下半身", "false",
        "0.8–1.2s", "第2刺取消点", "收招或 special", "缺失", "预留连段，程序后接。"),
    row("P1 接着做", S, "双手/全力刺（格挡奖励）", "spear_dual", U, "朝目标", "可跟一小步", "false",
        "0.8–1.2s", "完美格挡后", "走/闪", "缺失", "对应太刀双手砍。长矛可以是双手突进刺。"),
    row("P1 接着做", S, "格挡持有 / 收回", "defend / defend_end", U, "朝镜头", "可后退", "hold / false",
        "收回 0.2–0.35s", "X", "刺、走、闪", "缺失", "矛横挡或杆挡。"),
    row("P1 接着做", S, "完美格挡成功", "parry_success", U, "朝目标", "下半身", "false", "0.3–0.5s",
        "格挡窗口", "spear_dual 或走", "缺失", ""),
    row("P1 接着做", S, "蓄力持有 / 蓄力前突", "aiming / charge_release", U, "朝目标", "可慢走", "hold / false",
        "放出 0.8–1.3s，位移可大", "按住攻击", "走/闪", "缺失", "蓄力刺，比普通更长。"),
    row("P1 接着做", S, "检视 / 挨打", "inspect / hurt", U, "看矛 / 被打", "站住", "true / false", "同太刀",
        "V / 被命中", "idle", "缺失", ""),
]
S = "长矛-全身"
ROWS += [
    row("P1 接着做", S, "拔矛 / 收矛", "draw / put_away", FB, "朝镜头", "小步", "false",
        "0.6–1.0s", "R", "idle；拔完才允许刺", "缺失", "矛从斜背到手。标入手帧。"),
    row("P1 接着做", S, "四向闪避", "dodge_f/b/l/r", FB, "朝镜头", "对应方向", "false",
        "0.25–0.40s", "走/后摇", "idle/走", "缺失", "持矛滚或垫步，注意矛长。"),
    row("P1 接着做", S, "特殊：前突刺", "special", FB, "朝目标", "向前大步", "false",
        "0.7–1.2s，出刀在突进后半", "特殊键", "走/刺起手", "缺失", "原文特殊攻击。长矛主特殊建议就是这一记。"),
    row("P2 预留", S, "特殊：横扫（可选第二特殊）", "special_sweep", FB, "朝目标", "小步", "false",
        "0.8–1.2s，横的判定", "第二特殊或蓄力变招", "走", "缺失", "需要再开一个键才做。默认先做前突。"),
]

# =====================================================================
# 第一人称：和 3P 同模组名，方便对齐出刀帧
# =====================================================================
S = "FP太刀"
ROWS += [
    row("P1 接着做", S, "待机/拔/收/检视", "idle / draw / put_away / inspect", FP, "镜头", "—", "loop 或 false",
        "时长对齐 3P", "同 3P 键", "同 3P", "有片可精修", "文件 fp_singlesword。idle 现名 static_idle。"),
    row("P0 先做", S, "左手左砍 / 右手左砍 / 右手右砍 / 左手右砍", "attack1 / attack3 / attack4 / attack2", FP, "镜头", "—", "false",
        "必须和 3P 同一秒出刀", "同 3P 键位", "同 3P 衔接",
        "有占位", "attack3、attack4 现在是镜像生成，要手 K。出刀秒数写成和 3P 一样。"),
    row("P0 先做", S, "双手砍", "attack_dual", FP, "镜头", "—", "false", "对齐 3P", "完美格挡后", "走", "有占位", "手 K，不要混。"),
    row("P0 先做", S, "格挡 / 收回", "defend_1 / defend_end", FP, "镜头", "—", "hold / false", "对齐 3P", "X", "砍/走",
        "有占位", "收回缺失。"),
    row("P1 接着做", S, "蓄力持有 / 放出 / 完美格挡 / 挨打",
        "aiming / charge_release / parry_success / hurt", FP, "镜头", "—",
        "同 3P", "同 3P", "同 3P 键", "同 3P 衔接", "有占位", "放出、完美、挨打缺失。"),
    row("P1 接着做", S, "镜头晃：走四向 / 跑 / 跳两段", "walk_f/b/l/r, run_*, jump_up, jump_down", FP, "镜头", "—",
        "true/false", "轻晃即可", "移动", "移动", "只有前进", "现只有 root 前进晃。后退侧移要不同晃法。"),
    row("P0 先做", S, "四向闪避", "dodge_f/b/l/r", FP, "镜头", "—", "false", "对齐 3P 时长", "Shift", "idle", "缺失", ""),
]

S = "FP双刀"
ROWS += [
    row("P1 接着做", S, "待机/拔刀/检视", "idle / draw / inspect", FP, "镜头", "—", "loop/false", "对齐 3P 双刀",
        "R/V", "idle", "有片可精修", "fp_doublesword。收刀 put_away 缺失，程序现在是 null。"),
    row("P0 先做", S, "收刀 / 格挡 / 收回 / 蓄力", "put_away / defend / defend_end / aiming", FP, "镜头", "—",
        "false/hold", "对齐 3P", "R/X/按住", "对应衔接", "缺失", "程序 DOUBLE 这些字段全是 null，交了才能接。"),
    row("P0 先做", S, "四向砍 + 双手砍", "attack1 / attack3 / attack2 / attack4 / attack_dual", FP, "镜头", "—", "false",
        "对齐 3P 双刀（注意 3P 右键第一刀是 attack3）", "同 3P 双刀键", "同 3P",
        "有占位", "attack1 现 2.4s、attack2 现 4.1s，过长，按 3P 1.6s 重做。3/4 是镜像。"),
    row("P0 先做", S, "侧移/后退镜头晃 + 跳两段 + 闪避", "walk_b/l/r, jump_up/down, dodge_*", FP, "镜头", "—",
        "同 3P", "侧移时尤其要有", "移动/Shift", "移动", "缺失", "对应「向前看双刀侧身移动」的第一人称。"),
]

S = "FP长矛"
ROWS += [
    row("P1 接着做", S, "矛 FP 全套与 3P 同名",
        "idle, walk_f/b/l/r, run_*, jump_up, jump_down, land, draw, put_away, "
        "spear_l1, spear_l2, spear_r1, spear_r2, spear_dual, defend, defend_end, "
        "parry_success, aiming, charge_release, inspect, hurt, dodge_f/b/l/r, special",
        FP, "镜头", "—", "各片同 3P", "出刀秒数与 3P 一致", "同 3P 键", "同 3P 衔接",
        "缺失", "新文件 fp_spear。先做刺四刀+挡+拔收+前突，走四向其次。"),
]


def paint(ws, i, data):
    for c, v in enumerate(data, 1):
        cell = ws.cell(i, c, v)
        cell.font = CELL
        cell.alignment = WRAP
        cell.border = THIN
    ws.cell(i, 1).alignment = Alignment(horizontal="center", vertical="center")
    ws.cell(i, 1).font = Font(name="Microsoft YaHei", size=12)
    pri, wset, now = data[1], data[2], data[12]
    if pri in PRI_FILL:
        x = ws.cell(i, 2)
        x.fill = PRI_FILL[pri]
        x.font = Font(name="Microsoft YaHei", size=8, color="FFFFFF", bold=True)
    if wset in SET_FILL:
        ws.cell(i, 3).fill = SET_FILL[wset]
    if now in NOW_FILL:
        ws.cell(i, 13).fill = NOW_FILL[now]
    ws.row_dimensions[i].height = 38


def header(ws):
    for c, name in enumerate(HEAD, 1):
        cell = ws.cell(1, c, name)
        cell.fill = HEAD_FILL
        cell.font = HEAD_FONT
        cell.alignment = Alignment(wrap_text=True, vertical="center", horizontal="center")
        cell.border = THIN
    ws.row_dimensions[1].height = 32
    ws.freeze_panes = "A2"


WIDTHS = [7, 12, 14, 22, 36, 22, 14, 12, 16, 22, 32, 32, 18, 42]


def finish_sheet(ws, n):
    ws.auto_filter.ref = f"A1:N{n}"
    for i, w in enumerate(WIDTHS, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    dv = DataValidation(type="list", formula1='"☐,☑"', allow_blank=True)
    ws.add_data_validation(dv)
    dv.add(f"A2:A{n}")


def fill_sheet(ws, pred):
    header(ws)
    n = 2
    for data in ROWS:
        if pred(data):
            paint(ws, n, data)
            n += 1
    finish_sheet(ws, n - 1)


def main():
    wb = Workbook()

    # ----- 0 怎么用 -----
    w0 = wb.active
    w0.title = "0-怎么用"
    w0["A1"] = "动作模组接口表。招式怎么挥留给美术；这里只定名字、朝向、谁接谁。"
    w0["A1"].font = Font(name="Microsoft YaHei", size=14, bold=True, color="1F4E79")
    w0.merge_cells("A1:B1")
    how = [
        "",
        "你现在只有前进。缺的不是「再做一个走」，而是：人仍然朝镜头，脚往别的方向迈。",
        "  walk_f  脸朝镜头，往前走",
        "  walk_b  脸朝镜头，往后退   ← 向前看然后后退",
        "  walk_l / walk_r  脸朝镜头，左右侧移   ← 双刀侧身移动做双刀那一套",
        "  run_*  同上四向跑",
        "  jump_up  只负责蹬地到最高点",
        "  jump_down  只负责下落循环",
        "  land  落地缓冲后再回走/待机",
        "",
        "上下半身同时播：脚在 walk_b，手可以 attack1。所以走四向全部做下半身，砍全部做上半身。",
        "拔刀、收刀、闪避做全身（会盖住走）。",
        "",
        "太刀（单剑按太刀做）左右路：",
        "  左键：左手左砍 attack1 → 右手左砍 attack3 （→ 预留 attack5）",
        "  右键：右手右砍 attack4 → 左手右砍 attack2 （→ 预留 attack6）",
        "  左右键可以互相插（1 接 4，4 接 1），这就是交替连招。",
        "  完美格挡成功 → 双手砍 attack_dual（不进普通第 2 刀）。",
        "",
        "双刀：左键 attack_1 → attack2；右键 attack3 → attack4。侧移用双刀自己的 walk_l/r。",
        "长矛：左刺 spear_l1→l2，右刺 spear_r1→r2，同样可左右插。特殊默认「前突刺 special」。",
        "",
        "文件：",
        "  太刀 3P  third_person_singlesword.animation.json",
        "  双刀 3P  third_person_doublesword.animation.json",
        "  长矛 3P  third_person_spear.animation.json（新）",
        "  FP 各一套：fp_singlesword / fp_doublesword / fp_spear",
        "",
        "旧名对照（程序会认新名，旧名可暂时留着）：",
        "  static_idle → idle    walk_hold → walk_f    run_hold → run_f",
        "  jump → jump_up    fall → jump_down",
        "",
        "主表是「1-全部模组」。2/3/4/5 是按武器拆的同一批行。先筛 现状=缺失 和 优先级=P0。",
        "完成列勾 ☑。",
    ]
    for i, line in enumerate(how, 2):
        w0.cell(i, 1, line).font = Font(name="Microsoft YaHei", size=10)
    w0.column_dimensions["A"].width = 108

    w0["A34"] = "太刀衔接（程序按这个接，美术按这个做取消点）"
    w0["A34"].font = Font(name="Microsoft YaHei", size=12, bold=True, color="1F4E79")
    chain = [
        ["当前刀", "再按左键", "再按右键", "按格挡", "按闪避", "松手走到可取消点"],
        ["待机/走/跑", "左手左砍 attack1", "右手右砍 attack4", "defend", "dodge 四向", "—"],
        ["左手左砍 attack1", "右手左砍 attack3", "右手右砍 attack4", "defend", "dodge", "walk_* / idle"],
        ["右手左砍 attack3", "（预留 attack5 / 重复起手）", "右手右砍 attack4", "defend", "dodge", "walk_*"],
        ["右手右砍 attack4", "左手左砍 attack1", "左手右砍 attack2", "defend", "dodge", "walk_*"],
        ["左手右砍 attack2", "左手左砍 attack1", "（预留 attack6 / 重复起手）", "defend", "dodge", "walk_*"],
        ["双手砍 attack_dual", "不接普通刀", "不接普通刀", "defend", "dodge", "walk_*"],
        ["defend 持有", "不可砍", "不可砍", "继续挡", "dodge 取消挡", "defend_end → idle/走"],
        ["完美格挡 parry_success", "双手砍", "双手砍", "—", "dodge", "双手砍或走"],
    ]
    for r0, line in enumerate(chain):
        for c, v in enumerate(line, 1):
            cell = w0.cell(35 + r0, c, v)
            cell.font = Font(name="Microsoft YaHei", size=9, bold=(r0 == 0))
            cell.alignment = WRAP
            cell.border = THIN
            if r0 == 0:
                cell.fill = HEAD_FILL
                cell.font = HEAD_FONT
    w0.column_dimensions["B"].width = 28
    w0.column_dimensions["C"].width = 28
    w0.column_dimensions["D"].width = 16
    w0.column_dimensions["E"].width = 14
    w0.column_dimensions["F"].width = 22

    w0["A46"] = "长矛衔接（同样结构，刺代替砍）"
    w0["A46"].font = Font(name="Microsoft YaHei", size=12, bold=True, color="1F4E79")
    schain = [
        ["当前", "左键", "右键", "格挡", "闪避", "特殊"],
        ["待机/走", "spear_l1", "spear_r1", "defend", "dodge_*", "special 前突"],
        ["spear_l1", "spear_l2", "spear_r1", "defend", "dodge_*", "special"],
        ["spear_l2", "预留 l3", "spear_r1", "defend", "dodge_*", "special"],
        ["spear_r1", "spear_l1", "spear_r2", "defend", "dodge_*", "special"],
        ["spear_r2", "spear_l1", "预留 r3", "defend", "dodge_*", "special"],
        ["完美格挡", "spear_dual", "spear_dual", "—", "dodge_*", "—"],
    ]
    for r0, line in enumerate(schain):
        for c, v in enumerate(line, 1):
            cell = w0.cell(47 + r0, c, v)
            cell.font = Font(name="Microsoft YaHei", size=9, bold=(r0 == 0))
            cell.alignment = WRAP
            cell.border = THIN
            if r0 == 0:
                cell.fill = HEAD_FILL
                cell.font = HEAD_FONT

    # ----- 1 全部 -----
    w1 = wb.create_sheet("1-全部模组", 1)
    header(w1)
    for i, data in enumerate(ROWS, 2):
        paint(w1, i, data)
    finish_sheet(w1, 1 + len(ROWS))

    fill_sheet(wb.create_sheet("2-太刀"), lambda d: "太刀" in d[2] or d[2] == "FP太刀")
    fill_sheet(wb.create_sheet("3-双刀"), lambda d: "双刀" in d[2] or d[2] == "FP双刀")
    fill_sheet(wb.create_sheet("4-长矛"), lambda d: "长矛" in d[2] or d[2] == "FP长矛")
    fill_sheet(wb.create_sheet("5-只有下半身走跳"), lambda d: d[2].startswith("下半身"))

    # 计数
    ws = wb.create_sheet("6-计数")
    ws["A1"] = "条数"
    ws["A1"].font = Font(name="Microsoft YaHei", size=12, bold=True, color="1F4E79")
    ws["A3"] = "现状"
    ws["B3"] = "条数"
    ws["A3"].fill = HEAD_FILL
    ws["B3"].fill = HEAD_FILL
    ws["A3"].font = HEAD_FONT
    ws["B3"].font = HEAD_FONT
    nows = ["只有前进", "缺失", "有占位", "有片可精修", "程序还没接这个名字"]
    for i, n in enumerate(nows, 4):
        ws.cell(i, 1, n).fill = NOW_FILL[n]
        ws.cell(i, 1).font = CELL
        ws.cell(i, 2, f"=COUNTIF('1-全部模组'!M:M,A{i})").font = CELL
    ws["A11"] = "武器套"
    ws["B11"] = "条数"
    ws["A11"].fill = HEAD_FILL
    ws["B11"].fill = HEAD_FILL
    ws["A11"].font = HEAD_FONT
    ws["B11"].font = HEAD_FONT
    sets = list(dict.fromkeys(d[2] for d in ROWS))
    for i, n in enumerate(sets, 12):
        ws.cell(i, 1, n).fill = SET_FILL.get(n, PatternFill())
        ws.cell(i, 1).font = CELL
        ws.cell(i, 2, f"=COUNTIF('1-全部模组'!C:C,A{i})").font = CELL
    ws["A26"] = "总计"
    ws["B26"] = "=COUNTA('1-全部模组'!E:E)-1"
    ws["A26"].font = Font(bold=True, name="Microsoft YaHei")
    ws.column_dimensions["A"].width = 24
    ws.column_dimensions["B"].width = 10

    OUT.parent.mkdir(parents=True, exist_ok=True)
    wb.save(OUT)
    COPY.parent.mkdir(parents=True, exist_ok=True)
    wb.save(COPY)
    print("rows", len(ROWS))
    print(OUT)
    print(COPY)


if __name__ == "__main__":
    main()
