DESCRIPTION:
-----------
INSTRAL is a tool for placement of new species in the given set of gene trees and a backbone tree.


INSTRAL finds the species tree that has the maximum number of shared induced quartet trees with the set of gene trees.


The current code corresponds to **INSTRAL** (see below for the publication).
The algorithm was designed by Siavash Mirarab and Maryam Rabiee.

Email: `mrabieeh@ucsd.edu` for questions.



##### Publications:

- The original algorithm is described in:
	- Maryam Rabiee, Siavash Mirarab, INSTRAL: Discordance-Aware Phylogenetic Placement Using Quartet Scores, Systematic Biology , syz045, [https://doi.org/10.1093/sysbio/syz045](https://doi.org/10.1093/sysbio/syz045)
	




INSTALLATION:
-----------
There is no installation required to run INSTRAL.
You simply need to download the [zip file](https://github.com/maryamrabiee/INSTRAL/archive/master.zip)
and extract the contents to a folder of your choice. Alternatively, you can clone the [github repository](https://github.com/maryamrabiee/INSTRAL). You can run `make.sh` to build the project or simply use the jar file that is included with the repository.

INSTRAL is a java-based application, and should run in any environment (Windows, Linux, Mac, etc.) as long as java is installed. Java 1.5 or later is required. We have tested INSTRAL only on Linux and MAC.

To test your installation, go to the place where you put the uncompressed INSTRAL, and run:

```
java -Djava.library.path=. -jar __instral.jar__ 
```

There are sample input files under `test_data/` that can be used.

INSTRAL can be run from any directory. You just need to run `java -jar /path/to/astral/__instral.jar__`.
Also, you can move `__instral.jar__` to any location you like and run it from there, but note that you need
to move the `lib` directory as well.

EXECUTION:
-----------
INSTRAL currently has no GUI. You need to run it through the command-line. In a terminal, go the location where you have downloaded the software, and issue the following command:

```
  java -Djava.library.path=. -jar __instral.jar__ -C
```

This will give you a list of options available in ASTRAL.

To find the species tree given a set of gene trees in a file called `in.tree`, use:

```
java -Djava.library.path=. -jar __instral.jar__ -i in.tree -f backbone.tre --placement new_species_label -o out.tre -C
```

The branch label will be outputted to the standard output. 

```
java -Djava.library.path=. -jar __instral.jar__ -i in.tree -f backbone.tre --placement new_species_label -o out.tre > branch.br
```

To save the logs (**also recommended**), run:

```
java -Djava.library.path=. -jar __instral.jar__ -i in.tree -f backbone.tre --placement new_species_label -o out.tre > branch.br 2>out.log
```

sample run with data provided:

```
java -Djava.library.path=. -jar __instral.jar__ -i main/test_data/1KP-genetrees.tre --placement Oryza_sativa -f main/test_data/1KP-estimated-speciestree.Oryza_sativa.pr -o out.tre 2> out.log
```


###### Input: 
* The input gene trees and backbone tree are in the Newick format
* The input trees can have missing taxa, polytomies (unresolved branches).
*  Taxon names cannot have quotation marks in their names (sorry!). This means you also cannot have weird characters like ? in the name (underscore is fine).


###### Output: 
The output in is Newick format and gives: 

* stdout is the branch label of the insertion position
* the species tree topology, 
* It can also annotate branches with length, support and other quantities, such as quartet support, as described in the [tutorial](astral-tutorial.md).


### Multiple Insertions:
If the input gene trees have more than one species compared to backbone tree, you need to run INSTRAL once for each new species and then combine the results. You can use "multiple_placements.sh". You need Newick utilities and Dendropy package installed before running it.

```
./multiple_placements.sh estimatedgenetrees.tre backbone.tree outdir/ final_tree.tree 
```
single placements of each species can be found in the output directory.

A sample run for inserting two species :

```
./multiple_placements.sh main/test_data/1KP-genetrees.tre main/test_data/1KP-estimated-speciestree.Oryza_sativa.Thuidium_delicatulum.pr outdir/ final.tre .
```
It will insert the two species, Oryza_sativa and Thuidium\_delicatulum  that are missing from the backbone to it. The final tree is final.tre in outdir/.

### Resolving polytomies:
If you wanted to resolve polytomies, you can use Constrained ASTRAL and define the INSTRAL result as a constraint tree and let ASTRAL resolve it using the gene trees. The code is available [here](https://github.com/maryamrabiee/Constrained-search). For getting resolution of polytomies you can run:

```
java -jar astral.5.6.9.jar -i estimatedgenetrees.tre -o resolved-speciestree.tree -j contraint.tree 2> log.txt
```

resolved-speciestree.tree contains the final resolved tree and the constraint tree should be the final tree generated by running INSTRAL multiple times, independently.

### Memory:
For big datasets (say more than 200 taxa), increasing the memory available to Java can result in speedups. Note that you should give Java only as much free memory as you have available on your machine. So, for example, if you have 3GB of free memory, you can invoke INSTRAL using the following command to make all the 3GB available to Java:

```
java -Xmx3000M -Djava.library.path=. -jar __instral.jar__ -i in.tree
```


Bug Reports:
-----------
contact ``mrabieeh@ucsd.edu``
