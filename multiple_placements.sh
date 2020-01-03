genetrees=$1
backbone=$2
outdir=$3
out=$4
repo=$5
test $# -ne "5" && echo "Usage: $0 [GENE TREES FILE] [BACKBONE TREE FILE] [OUTPUT DIR] [FINAL TREE NAME] [PATH TO THE REPOSITORY]" && exit 1

comm -2 -3 <(nw_labels -I $genetrees | sort | uniq ) <(nw_labels -I $backbone) > $outdir/new_labels

for label in $(cat $outdir/new_labels);do 
	java -jar -Djava.library.path=$repo/lib/ -jar $repo/instral.5.13.4.jar -i $genetrees -f $backbone -o $outdir/out-$label --placement $label --no-scoring -C -T1 2> $outdir/out-$label.log > $label.br
	echo -n "$label " >> $outdir/placement-map
	cat $label.br >> $outdir/placement-map	
done

python3 combine_insertions.py $backbone $outdir/placement-map $outdir/$out

