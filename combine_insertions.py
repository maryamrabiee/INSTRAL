import dendropy
import sys
import random

def read_placement_map(lines):
	placements = {}
	for line in lines:
		if line.split()[1] in placements:
			placements[line.split()[1]].append(line.split()[0])
		else:
			placements[line.split()[1]] = [line.split()[0]]
	return placements


def update_tree(backbone, placements):
	index = 0
	for node in backbone.postorder_node_iter():
		if node.is_leaf():
			label = node.taxon.label
		else:
			label = node.label
		tns = backbone.taxon_namespace

		if node.parent_node:
			if label in placements.keys():
				p = node.parent_node
				new_node = p.insert_new_child(len(p.child_nodes())+1, label='P'+ label)
				index += 1
				node.parent_node = new_node
				for i,leaf in enumerate(placements[label]):
					new_node.insert_new_child(len(new_node.child_nodes())+i, taxon=tns.new_taxon(leaf))

		else: #root case
			p = node
			if label in placements.keys():
				for i,leaf in enumerate(placements[label]):
					p.insert_new_child(len(p.child_nodes())+i, taxon=tns.new_taxon(leaf))

# inputs : backbone tree, placement map file, file to store the updated tree

if "__main__" == __name__:

	random.seed(7899256)
	backbone = dendropy.Tree.get(path=sys.argv[1], schema='newick')

	with open(sys.argv[2]) as f:
		lines = f.readlines()
	lines = [x.strip() for x in lines]

	placements = read_placement_map(lines)
	print(sorted([len(v) for k,v in placements.items()],reverse=True)[:200])

	
	
	update_tree(backbone, placements)
	
	print(backbone.as_string(schema='newick'))

	with open(sys.argv[3],"w+") as f:
		f.write(backbone.as_string(schema='newick'))


