import sys

if "__main__" == __name__:
	with open(sys.argv[1]) as f:
		content = f.readlines()[0].strip()

	new_tree = ""
	N = 1
	for c in content:
		new_tree += c
		if c == ")":
			new_tree += "N" + str(N)
			N += 1
	print(new_tree)


