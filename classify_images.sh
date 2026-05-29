#!/bin/bash
# 按课时段分类监控图像
# 使用说明: ./classify_images.sh [日期目录]
# 例如: ./classify_images.sh data/2026-0526

set -e

DATE_DIR="$1"
if [ -z "$DATE_DIR" ] || [ ! -d "$DATE_DIR" ]; then
  echo "错误: 请指定有效的日期目录"
  echo "用法: $0 <日期目录>"
  exit 1
fi

DATE_NAME=$(basename "$DATE_DIR")
echo "===== 正在分类: $DATE_NAME ====="

# 课时段定义 (HHMM 格式)
# 根据标准初中课表设置，每节40分钟，课间10分钟
declare -A PERIODS
PERIODS["arrival"]="早读-到校"
PERIODS["period1"]="第1节"
PERIODS["period2"]="第2节"
PERIODS["period3"]="第3节"
PERIODS["recess"]="课间操"
PERIODS["period4"]="第4节"
PERIODS["period5"]="第5节"
PERIODS["lunch"]="午餐-午休"
PERIODS["period6"]="第6节"
PERIODS["period7"]="第7节"
PERIODS["period8"]="第8节"
PERIODS["afterclass"]="课外活动-放学"

# 时间范围定义 [开始, 结束) 左闭右开
# 格式: PERIOD_RANGES["段名"]="开始HHMM 结束HHMM"
declare -A PERIOD_RANGES
PERIOD_RANGES["arrival"]="0600 0740"
PERIOD_RANGES["period1"]="0740 0820"
PERIOD_RANGES["period2"]="0820 0900"
PERIOD_RANGES["period3"]="0900 0950"
PERIOD_RANGES["recess"]="0950 1010"
PERIOD_RANGES["period4"]="1010 1050"
PERIOD_RANGES["period5"]="1050 1140"
PERIOD_RANGES["lunch"]="1140 1400"
PERIOD_RANGES["period6"]="1400 1440"
PERIOD_RANGES["period7"]="1440 1530"
PERIOD_RANGES["period8"]="1530 1620"
PERIOD_RANGES["afterclass"]="1620 1900"

# 创建子目录
echo "创建子目录..."
for key in "${!PERIODS[@]}"; do
    dir_name="${PERIODS[$key]}"
    mkdir -p "$DATE_DIR/$dir_name"
done

# 统计变量
total=0
moved=0
errors=0

# 遍历所有jpg文件
echo "开始分类文件..."
for img in "$DATE_DIR"/*.jpg; do
    [ -f "$img" ] || continue
    total=$((total + 1))

    filename=$(basename "$img")
    # 提取时间戳中的 HHMM
    # 支持两种文件名格式:
    #   新格式: 172_16_15_11_{20260527_070104}.jpg → 提取 {YYYYMMDD_HHMMSS} 中的 HHMM
    #   旧格式: 20260521063057_T85_0005A7C8.jpg  → 第9-12位 (0-indexed: 8-11)
    basename_noext="${filename%.*}"
    if [[ "$basename_noext" =~ \{[0-9]{8}_([0-9]{4})[0-9]{2}\} ]]; then
        timestamp="${BASH_REMATCH[1]}"
    else
        timestamp="${basename_noext:8:4}"
    fi

    assigned=false
    for key in "${!PERIOD_RANGES[@]}"; do
        range="${PERIOD_RANGES[$key]}"
        start_hhmm="${range:0:4}"
        end_hhmm="${range:5:4}"

        if [ "$timestamp" -ge "$start_hhmm" ] && [ "$timestamp" -lt "$end_hhmm" ]; then
            dir_name="${PERIODS[$key]}"
            mv "$img" "$DATE_DIR/$dir_name/"
            moved=$((moved + 1))
            assigned=true
            break
        fi
    done

    if [ "$assigned" = false ]; then
        # 超出定义的时段范围 - 归入"课前课后"
        mkdir -p "$DATE_DIR/课前课后"
        mv "$img" "$DATE_DIR/课前课后/"
        errors=$((errors + 1))
    fi
done

echo ""
echo "===== 分类完成: $DATE_NAME ====="
echo "总文件数: $total"
echo "已归入课节: $moved"
echo "课前课后: $errors"
echo ""

# 显示各目录统计
echo "各时段分布:"
for key in arrival period1 period2 period3 recess period4 period5 lunch period6 period7 period8 afterclass; do
    dir_name="${PERIODS[$key]}"
    count=$(ls "$DATE_DIR/$dir_name" 2>/dev/null | wc -l)
    range="${PERIOD_RANGES[$key]}"
    start_h="${range:0:2}:${range:2:2}"
    end_h="${range:5:2}:${range:7:2}"
    echo "  $start_h-$end_h  $dir_name : $count 张"
done

if [ -d "$DATE_DIR/课前课后" ]; then
    count=$(ls "$DATE_DIR/课前课后" | wc -l)
    echo "  课前课后 : $count 张"
fi
