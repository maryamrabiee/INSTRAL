DESCRIPTION:
-----------
 INSTRAL (Insertion of New Species using asTRAL), a method that extends ASTRAL to enable phylogenetic placement. INSTRAL is capable of adding a new species onto an existing species tree after sequences from the new species have already been added to gene trees; thus, INSTRAL is complementary to existing placement methods such as pplacer and EPA that update gene trees with new sequences.


#### Documentations

1. The rest of this README file
- **ASTRAL's [tutorial](astral-tutorial.md)**.
- Publications below have scientific details
- A [developer guide](developer-guide.md).

##### Publications:

- INTRAL original paper is:
	* M. Rabiee, S. Mirarab,  Instral:  Discordance-aware phylogenetic placement usingquartet scores,  bioRxiv (2018) [doi:10.1101/432906](doi.org/10.1101/432906).

- The code corresponds to ASTRAL-III, described in:
	* Zhang, Chao, Maryam Rabiee, Erfan Sayyari, and Siavash Mirarab. “ASTRAL-III: Polynomial Time Species Tree Reconstruction from Partially Resolved Gene Trees.” BMC Bioinformatics 19, no. S6 (May 8, 2018): 153. https://doi.org/10.1186/s12859-018-2129-y.




INSTALLATION:
-----------
There is no installation required to run INSTRAL.
You simply need to download the [zip file](https://github.com/maryamrabiee/INSTRAL/raw/master/__instral.zip__)
and extract the contents to a folder of your choice. Alternatively, you can clone the [github repository](https://github.com/maryamrabiee/INSTRAL/). You can run `make.sh` to build the project or simply use the jar file that is included with the repository.

INSTRAL is a java-based application, and should run in any environment (Windows, Linux, Mac, etc.) as long as java is installed. Java 1.5 or later is required. We have tested INSTRAL only on Linux and MAC.

To test your installation, go to the place where you put the uncompressed INSTRAL, and run:

```
java -Djava.library.path=. -jar __instral.jar__ -i test_data/song_primates.424.gene.tre
```

This should quickly finish. There are also other sample input files under `test_data/` that can be used.

INSTRAL can be run from any directory. You just need to run `java -jar /path/to/instral/__instral.jar__`.
Also, you can move `__instral.jar__` to any location you like and run it from there, but note that you need
to move the `lib` directory as well.

EXECUTION:
-----------
INSTRAL currently has no GUI. You need to run it through the command-line. In a terminal, go the location where you have downloaded the software, and issue the following command:

```
  java -Djava.library.path=. -jar __instral.jar__
```

This will give you a list of options available in INSTRAL.

To insert a new species to a given backbone tree in file called 'backbone.tre' based on a set of gene trees in a file called `in.tree`, use:

```
java -Djava.library.path=. -jar __instral.jar__ -i in.tree -f backbone.tre --placement new_species_label
```

The results will be outputted to the standard output. To save the results in a file use the `-o` option (**Strongly recommended**):

```
java -Djava.library.path=. -jar __instral.jar__ -i in.tree -f backbone.tre --placement new_species_label -o out.tre
```
To save the logs (**also recommended**), run:

```
java -Djava.library.path=. -jar __instral.jar__ -i in.tree -f backbone.tre --placement new_species_label 2>out.log
```

###### Input: 
* The input gene trees are in the Newick format
* The input trees can have missing taxa, polytomies (unresolved branches)
*  Taxon names cannot have quotation marks in their names (sorry!). This means you also cannot have weird characters like ? in the name (underscore is fine).
   
###### Output: 
The output in is Newick format and gives: 

* the species tree topology, 
* branch lengths in coalescent units (only for internal branches or for terminal branches if that species has multiple individuals),
* branch supports measured as [local posterior probabilities](). 
* It can also annotate branches with other quantities, such as quartet support, as described in the [tutorial](astral-tutorial.md).



### Memory:
For big datasets (say more than 200 taxa), increasing the memory available to Java can result in speedups. Note that you should give Java only as much free memory as you have available on your machine. So, for example, if you have 3GB of free memory, you can invoke INSTRAL using the following command to make all the 3GB available to Java:

```
java -Xmx3000M -Djava.library.path=. -jar __instral.jar__ -i in.tree
```

Acknowledgment
-----------
INSTRAL code uses bytecode and some reverse engineered code from PhyloNet package (with permission from the authors).


