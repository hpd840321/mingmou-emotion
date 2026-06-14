#!/usr/bin/env python3
"""
性别不一致学生拆分脚本
====================
对 gender 混合的学生（如 50% 男 + 50% 女），将 minority gender 的
face_record 拆分到新创建的学生记录，并更新 person registry。

用法:
  python3 scripts/split_gender_mismatch.py
  python3 scripts/split_gender_mismatch.py --dry-run

策略:
  1. 对每个 gender 混合的学生，确定 majority gender
  2. 收集 minority gender 的 persons (lib_face_id) 列表
  3. 为这些 persons 创建新的学生记录
  4. 将 minority 的 face_record 重新关联到新学生
  5. 更新 /tmp/pipeline_persons.json registry
"""

import os, sys, json
import argparse
import logging
from datetime import datetime
from collections import defaultdict

import psycopg2

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('split_gender')

DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')
PERSONS_FILE = '/tmp/pipeline_persons.json'


def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)


def get_student_gender_stats(cur):
    """获取每个学生的 gender 分布"""
    cur.execute("""
        SELECT student_id, s.name, s.student_no,
               SUM(CASE WHEN gender = 0 THEN 1 ELSE 0 END) AS female,
               SUM(CASE WHEN gender = 1 THEN 1 ELSE 0 END) AS male,
               COUNT(*) AS total
        FROM face_record fr
        JOIN student s ON fr.student_id = s.id
        WHERE fr.student_id IS NOT NULL AND fr.gender IS NOT NULL
        GROUP BY fr.student_id, s.name, s.student_no
        HAVING MIN(gender) != MAX(gender)
        ORDER BY student_id
    """)
    return cur.fetchall()


def get_face_records_for_student_gender(cur, student_id, gender):
    """获取某个学生下特定性别的 face_record（含 person_id）"""
    cur.execute("""
        SELECT fr.id, fr.lib_face_id
        FROM face_record fr
        WHERE fr.student_id = %s AND fr.gender = %s
    """, (student_id, gender))
    return cur.fetchall()


def get_next_student_id(cur):
    """获取下一个可用 student_id"""
    cur.execute("SELECT COALESCE(MAX(id), 0) + 1 FROM student")
    return cur.fetchone()[0]


def get_names_for_new_student(cur):
    """生成新学生名和学号"""
    cur.execute("SELECT COALESCE(MAX(CAST(SUBSTRING(student_no FROM 4) AS INTEGER)), 0) FROM student")
    max_no = cur.fetchone()[0]
    new_no = max_no + 1
    return f'学生{new_no:04d}', f'stu{new_no:04d}'


def update_person_registry(moved_persons, old_student_id, new_student_id, new_name):
    """更新 /tmp/pipeline_persons.json 中的 student_id 映射"""
    if not os.path.exists(PERSONS_FILE):
        log.warning("  person registry 不存在，跳过")
        return
    try:
        with open(PERSONS_FILE) as f:
            registry = json.load(f)
        persons = registry.get('persons', {})
        for pid in moved_persons:
            if pid in persons:
                persons[pid]['student_id'] = new_student_id
        registry['persons'] = persons
        with open(PERSONS_FILE, 'w') as f:
            json.dump(registry, f, indent=2)
        log.info("  person registry 已更新: %d 个 person -> student_%s (%s)",
                 len(moved_persons), new_student_id, new_name)
    except Exception as e:
        log.warning("  更新 person registry 失败: %s", e)


def main():
    parser = argparse.ArgumentParser(description='拆分性别不一致的学生')
    parser.add_argument('--dry-run', action='store_true', help='干跑，不写入 DB')
    parser.add_argument('--min-ratio', type=float, default=0.2,
                        help='minority 占比阈值（默认 0.2，即 <20% 的才拆）')
    args = parser.parse_args()

    conn = db_connect()
    cur = conn.cursor()

    mixed = get_student_gender_stats(cur)
    log.info("Gender 混合的学生: %d 个", len(mixed))

    total_split = 0
    total_new_students = 0
    total_moved_faces = 0

    log.info("=" * 60)
    log.info("开始拆分...")
    log.info("=" * 60)

    # 先获取可用的起始 student_id
    cur.execute("SELECT COALESCE(MAX(id), 0) + 1 FROM student")
    next_id = cur.fetchone()[0]
    cur.execute("SELECT COALESCE(MAX(CAST(SUBSTRING(student_no FROM 4) AS INTEGER)), 0) FROM student")
    max_no = cur.fetchone()[0]

    # 收集所有需要执行的操作（不提交）
    pending_ops = []  # [(new_id, new_name, new_no, old_sid, to_move, moved_pids), ...]

    for row in mixed:
        sid, sname, sno, female, male, total = row
        female, male = int(female), int(male)
        if female == 0 or male == 0:
            continue

        if female >= male:
            minority_g = 1
            minority_cnt = male
        else:
            minority_g = 0
            minority_cnt = female

        min_ratio = minority_cnt / total
        if min_ratio < args.min_ratio:
            log.info("  %s(#%s): minority=%d/%d (%.0f%%) 占比太小，跳过",
                     sname, sid, minority_cnt, total, min_ratio * 100)
            continue

        to_move = get_face_records_for_student_gender(cur, sid, minority_g)
        if not to_move:
            continue

        moved_pids = set()
        for fr_id, pid in to_move:
            if pid:
                moved_pids.add(pid)

        if args.dry_run:
            log.info("  [DRY] %s(#%s): %d男+%d女 → 拆分 %d 条 (person: %s)",
                     sname, sid, male, female, len(to_move),
                     ', '.join(sorted(moved_pids)[:3]) + ('...' if len(moved_pids) > 3 else ''))
            total_split += 1
            total_moved_faces += len(to_move)
            continue

        max_no += 1
        new_name = f'学生{max_no:04d}'
        new_no = f'stu{max_no:04d}'
        pending_ops.append((next_id, new_name, new_no, sid, to_move, moved_pids, male, female))
        next_id += 1

    if args.dry_run or not pending_ops:
        if not args.dry_run:
            log.info("没有需要拆分的记录")
        log.info("=" * 60)
        log.info("拆分完成!")
        if args.dry_run:
            log.info("  处理: %d 个混合学生", total_split)
            log.info("  新建: 0 个学生")
            log.info("  移动: %d 条 face_record", total_moved_faces)
        log.info("=" * 60)
        cur.close()
        conn.close()
        return

    # 批量执行所有操作（单次提交）
    batch_fr_ids = []  # [(new_id, fr_ids), ...]
    all_registry_updates = []  # [(moved_pids, old_sid, new_id, new_name), ...]

    for new_id, new_name, new_no, old_sid, to_move, moved_pids, male, female in pending_ops:
        cur.execute("""INSERT INTO student (id, name, student_no, status, class_id)
                       VALUES (%s, %s, %s, 'active', 1)""",
                    (new_id, new_name, new_no))
        fr_ids = [r[0] for r in to_move]
        cur.execute("UPDATE face_record SET student_id = %s WHERE id = ANY(%s)",
                    (new_id, fr_ids))
        moved_count = cur.rowcount
        batch_fr_ids.append((new_id, fr_ids))
        all_registry_updates.append((moved_pids, old_sid, new_id, new_name))
        log.info("  %s(#%s): %d男+%d女 → 拆分 %d 条到 %s(#%s) (person: %s)",
                 '?' if old_sid else '?', old_sid,
                 male, female, moved_count, new_name, new_id,
                 ', '.join(sorted(moved_pids)[:3]) + ('...' if len(moved_pids) > 3 else ''))
        total_split += 1
        total_new_students += 1
        total_moved_faces += moved_count

    # 更新 student_id 序列
    cur.execute("SELECT setval('student_id_seq', GREATEST(nextval('student_id_seq'), %s))", (next_id - 1,))

    # 一次提交所有 DB 操作
    conn.commit()

    # 提交后更新 person registry（文件操作，不影响 DB 事务）
    for moved_pids, old_sid, new_id, new_name in all_registry_updates:
        update_person_registry(moved_pids, old_sid, new_id, new_name)

    log.info("=" * 60)
    log.info("拆分完成!")
    log.info("  处理: %d 个混合学生", total_split)
    log.info("  新建: %d 个学生", total_new_students)
    log.info("  移动: %d 条 face_record", total_moved_faces)
    log.info("=" * 60)

    # 最终统计
    cur.execute("SELECT COUNT(*) FROM face_record WHERE student_id IS NOT NULL")
    linked = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM student")
    n_students = cur.fetchone()[0]
    log.info("当前状态: %d 学生, %d 条 face_record 已关联",
             n_students, linked)

    cur.close()
    conn.close()


if __name__ == '__main__':
    main()
