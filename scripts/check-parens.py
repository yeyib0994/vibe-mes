import io, sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()
depth = 0
line = 1
n = len(src)
i = 0
marks = []
while i < n:
    c = src[i]
    if c == '\n':
        line += 1
        i += 1
        continue
    if src.startswith('//', i):
        j = src.find('\n', i)
        i = j if j > 0 else n
        continue
    if src.startswith('/*', i):
        j = src.find('*/', i + 2)
        line += src[i:j].count('\n')
        i = j + 2
        continue
    if c == '"':
        i += 1
        while i < n and src[i] != '"':
            if src[i] == '\\':
                i += 1
            i += 1
        i += 1
        continue
    if c == "'":
        i += 1
        while i < n and src[i] != "'":
            if src[i] == '\\':
                i += 1
            i += 1
        i += 1
        continue
    if c == '(':
        depth += 1
    elif c == ')':
        depth -= 1
        if depth < 0:
            print('EXTRA CLOSE at line', line)
            depth = 0
    elif c == '{':
        pass
    i += 1
print('final paren depth:', depth)
