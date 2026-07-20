---
name: fib-skill
description: 计算斐波那契数列。当用户要求计算斐波那契数或相关数列时使用。
---
# 斐波那契技能

使用以下步骤计算斐波那契数列前 N 项：

1. 用 write_file 在工作区写入脚本 fib_skill.py，内容为：
   ```python
   import sys
   n = int(sys.argv[1]) if len(sys.argv) > 1 else 10
   a, b = 0, 1
   seq = []
   for _ in range(n):
       seq.append(a)
       a, b = b, a + b
   print(seq)
   ```
2. 用 exec 运行 `python fib_skill.py <N>`。
3. 把输出整理后告诉用户。
