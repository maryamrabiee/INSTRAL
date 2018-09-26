package phylonet.coalescent;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import phylonet.lca.SchieberVishkinLCA;
import phylonet.tree.model.MutableTree;
import phylonet.tree.model.TMutableNode;
import phylonet.tree.model.TNode;
import phylonet.tree.model.Tree;
import phylonet.tree.model.sti.STINode;
import phylonet.tree.model.sti.STITree;
import phylonet.tree.model.sti.STITreeCluster;
import phylonet.tree.util.Trees;
import phylonet.util.BitSet;

/**
 * Sets up the set X
 * 
 * @author smirarab
 * 
 */
public class WQDataCollection extends AbstractDataCollection<Tripartition>
implements Cloneable {

	/**
	 * A list that includes the cluster associated with the set of all taxa
	 * included in each gene tree
	 */
	List<STITreeCluster> treeAllClusters = new ArrayList<STITreeCluster>();

	/**
	 * Similarity matrices for individuals. Used for setting up set X
	 */
	Matrix geneMatrix;
	/**
	 * Similarity matrices for species. Used for setting up set X
	 */
	Matrix speciesMatrix;

	// Parameters of ASTRAL-II heuristics
	private boolean SLOW = false;
	private final double[] GREEDY_ADDITION_THRESHOLDS = new double[] { 0,
			1 / 100., 1 / 50., 1 / 20., 1 / 10., 1 / 5., 1 / 3. };
	private final int GREEDY_DIST_ADDITTION_LAST_THRESHOLD_INDX = 3;
	private final int GREEDY_ADDITION_MAX_POLYTOMY_MIN = 50;
	private final int GREEDY_ADDITION_MAX_POLYTOMY_MULT = 25;
	private final int GREEDY_ADDITION_DEFAULT_RUNS = 10;
	private final int GREEDY_ADDITION_MIN_FREQ = 5;
	private final double GREEDY_ADDITION_MIN_RATIO = 0.01;
	private final int GREEDY_ADDITION_MAX = 100;
	private final int GREEDY_ADDITION_IMPROVEMENT_REWARD = 2;
	private final int POLYTOMY_RESOLUTIONS = 3;
	private final double POLYTOMY_RESOLUTIONS_GREEDY_GENESAMPLE = 0.9;
	private List<Tree> geneTrees;
	private boolean outputCompleted;
	private String outfileName; 
	private final int POLYTOMY_RESOLUTIONS_SAMPLE_GRADIENT = 15000;
	private final int POLYTOMY_SIZE_LIMIT_MAX = 100000;
	private int polytomySizeLimit = POLYTOMY_SIZE_LIMIT_MAX;

	// Just a reference to gene trees from inference (for convinience).
	private List<Tree> originalInompleteGeneTrees;
	/**
	 * Gene trees after completion.
	 */
	private List<Tree> completedGeeneTrees;

	// A reference to user-spcified global options.
	private Options options;

	// Used for cpu parallelization in a loop in FormSetX. This is used in addExtraBipartitionByHeuristics
	//private ArrayList<Object> stringOutput = new ArrayList<Object>();

	public WQDataCollection(WQClusterCollection clusters,
			AbstractInference<Tripartition> inference) {
		this.clusters = clusters;
		this.SLOW = inference.getAddExtra() >= 2;
		this.originalInompleteGeneTrees = inference.trees;
		this.completedGeeneTrees = new ArrayList<Tree>();
		this.options = inference.options;
	}

	/**
	 * Once we have chosen a subsample with only one individual per species, we
	 * can use this metod to compute and add bipartitions from the input gene
	 * trees to set X. This is equivalent of ASTRAL-I set X computed for the
	 * subsample.
	 * 
	 * @param allGenesGreedy
	 * @param trees
	 * @param taxonSample
	 * @param greedy
	 *            is the greedy consensus of all gene trees
	 */
	private void addBipartitionsFromSignleIndTreesToX(Tree tr,
			Collection<Tree> baseTrees, TaxonIdentifier id) {

		Stack<STITreeCluster> stack = new Stack<STITreeCluster>();

		for (TNode node : tr.postTraverse()) {
			if (node.isLeaf()) {
				STITreeCluster cluster = GlobalMaps.taxonNameMap
						.getSpeciesIdMapper().getSTTaxonIdentifier()
						.getClusterForNodeName(node.getName());
				stack.add(cluster);
				addSpeciesBipartitionToX(cluster);

			} else {
				ArrayList<BitSet> childbslist = new ArrayList<BitSet>();

				BitSet bs = new BitSet(GlobalMaps.taxonNameMap
						.getSpeciesIdMapper().getSTTaxonIdentifier()
						.taxonCount());
				for (TNode child : node.getChildren()) {
					STITreeCluster pop = stack.pop();
					childbslist.add(pop.getBitSet());
					bs.or(pop.getBitSet());
				}

				/**
				 * Note that clusters added to the stack are currently using the
				 * global taxon identifier that has all individuals
				 */
				STITreeCluster cluster = GlobalMaps.taxonNameMap
						.getSpeciesIdMapper().getSTTaxonIdentifier()
						.newCluster();
				cluster.setCluster(bs);
				stack.add(cluster);

				// boolean bug = false;
				try {
					if (addSpeciesBipartitionToX(cluster)) {

					}
				} catch (Exception e) {
					// bug = true;
					// System.err.println("node : "+node.toString());
					// System.err.println("cluster : "+cluster);
					// System.err.println(childbslist.size());
					// System.err.println(childbslist);
					// System.err.println("bs : "+bs);
					e.printStackTrace();
				}

				/**
				 * For polytomies, if we don't do anything extra, the cluster
				 * associated with the polytomy may not have any resolutions in
				 * X. We don't want that. We use the greedy consensus trees and
				 * random sampling to add extra bipartitions to the input set
				 * when we have polytomies.
				 */

				if (childbslist.size() > 2) {
					BitSet remaining = (BitSet) bs.clone();
					remaining.flip(0, GlobalMaps.taxonNameMap
							.getSpeciesIdMapper().getSTTaxonIdentifier()
							.taxonCount());
					// if (bug) {
					// System.err.println(remaining);
					// }
					//
					boolean isRoot = remaining.isEmpty();
					int d = childbslist.size() + (isRoot ? 0 : 1);
					BitSet[] polytomy = new BitSet[d];
					int i = 0;
					for (BitSet child : childbslist) {
						polytomy[i++] = child;
					}
					if (!isRoot) {
						polytomy[i] = remaining;
					}

					// TODO: do multiple samples
					int gradient = Integer.MAX_VALUE;
					for (int ii = 0; ii < 3; ii++) {
						int b = this.clusters.getClusterCount();

						HashMap<String, Integer> randomSample = this.
								randomSampleAroundPolytomy(polytomy, GlobalMaps.taxonNameMap
										.getSpeciesIdMapper().getSTTaxonIdentifier());

						//					int sampleAndResolveRounds = 4;
						//					for (int j = 0; j < sampleAndResolveRounds; j++) {
						//						sampleAndResolve(polytomy,inputTrees, false, speciesMatrix, GlobalMaps.taxonNameMap
						//								.getSpeciesIdMapper()
						//								.getSTTaxonIdentifier(), false, true);
						//					}
						for (Tree gct : baseTrees) {

							for (BitSet restrictedBitSet : Utils.getBitsets(
									randomSample, gct)) {
								/**
								 * Before adding bipartitions from the greedy consensus
								 * to the set X we need to add the species we didn't
								 * sample to the bitset.
								 */
								restrictedBitSet = this.addbackAfterSampling(polytomy,
										restrictedBitSet, GlobalMaps.taxonNameMap
										.getSpeciesIdMapper()
										.getSTTaxonIdentifier());
								this.addSpeciesBitSetToX(restrictedBitSet);
							}
							gradient = this.clusters.getClusterCount() - b;
						}
						gradient = this.clusters.getClusterCount() - b;
					}

				}

			}
		}
	}

	/**
	 * How many rounds of sampling should we do? Completely arbitrarily at this
	 * point. Should be better explored.
	 * 
	 * @param userProvidedRounds
	 * @return
	 */
	private int getSamplingRepeationFactor(int userProvidedRounds) {
		if (userProvidedRounds < 1) {
			double sampling = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
					.meanSampling();
			int repeat = (int) (int) Math.ceil(Math.log(2 * sampling)
					/ Math.log(2));
			return repeat;
		} else {
			return userProvidedRounds;
		}

	}

	/**
	 * Completes an incomplete tree for the purpose of adding to set X
	 * Otherwise, bipartitions are meaningless.
	 * 
	 * @param tr
	 * @param gtAllBS
	 * @return
	 */
	Tree getCompleteTree(Tree tr, BitSet gtAllBS) {

		if (gtAllBS.cardinality() < 3) {
			throw new RuntimeException("Tree " + tr.toNewick()
					+ " has less than 3 taxa; it cannot be completed");
		}
		STITree trc = new STITree(tr);

		Trees.removeBinaryNodes(trc);

		for (int missingId = gtAllBS.nextClearBit(0); missingId < GlobalMaps.taxonIdentifier
				.taxonCount(); missingId = gtAllBS.nextClearBit(missingId + 1)) {

			int closestId = geneMatrix.getClosestPresentTaxonId(gtAllBS,
					missingId);

			STINode closestNode = trc.getNode(GlobalMaps.taxonIdentifier
					.getTaxonName(closestId));

			trc.rerootTreeAtNode(closestNode);
			Trees.removeBinaryNodes(trc);

			Iterator cit = trc.getRoot().getChildren().iterator();
			STINode c1 = (STINode) cit.next();
			STINode c2 = (STINode) cit.next();
			STINode start = closestNode == c1 ? c2 : c1;

			int c1random = -1;
			int c2random = -1;
			while (true) {
				if (start.isLeaf()) {
					break;
				}

				cit = start.getChildren().iterator();
				c1 = (STINode) cit.next();
				c2 = (STINode) cit.next();

				// TODO: what if c1 or c2 never appears in the same tree as
				// missing and closestId .
				if (c1random == -1) {
					c1random = GlobalMaps.taxonIdentifier.taxonId(Utils
							.getLeftmostLeaf(c1));
				}
				if (c2random == -1) {
					c2random = GlobalMaps.taxonIdentifier.taxonId(Utils
							.getLeftmostLeaf(c2));
				}
				int betterSide = geneMatrix.getBetterSideByFourPoint(
						missingId, closestId, c1random, c2random);
				if (betterSide == closestId) {
					break;
				} else if (betterSide == c1random) {
					start = c1;
					// Currently, c1random is always under left side of c1
					c2random = -1;
				} else if (betterSide == c2random) {
					start = c2;
					// Currently, c2random is always under left side of c2
					c1random = c2random;
					c2random = -1;
				}

			}
			if (start.isLeaf()) {
				STINode newnode = start.getParent().createChild(
						GlobalMaps.taxonIdentifier.getTaxonName(missingId));
				STINode newinternalnode = start.getParent().createChild();
				newinternalnode.adoptChild(start);
				newinternalnode.adoptChild(newnode);
			} else {
				STINode newnode = start.createChild(GlobalMaps.taxonIdentifier
						.getTaxonName(missingId));
				STINode newinternalnode = start.createChild();
				newinternalnode.adoptChild(c1);
				newinternalnode.adoptChild(c2);
			}
		}

		return trc;
	}

	/**
	 * Given a bitset that shows one side of a bipartition this method adds the
	 * bipartition to the set X. Importantly, when the input bitset has only one
	 * (or a sbuset) of individuals belonging to a species set, the other
	 * individuals from that species are also set to one before adding the
	 * bipartition to the set X. Thus, all individuals from the same species
	 * will be on the same side of the bipartition. These additions are done on
	 * a copy of the input bitset not the instance passed in.
	 * 
	 * @param stBitSet
	 * @return was the cluster new?
	 */
	// private boolean addSingleIndividualBitSetToX(final BitSet bs) {
	// STITreeCluster cluster = GlobalMaps.taxonIdentifier.newCluster();
	// cluster.setCluster(bs);
	// return this.addSingleIndividualBipartitionToX(cluster);
	// }
	private boolean addSpeciesBitSetToX(final BitSet stBitSet) {
		STITreeCluster cluster = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
				.getSTTaxonIdentifier().newCluster();
		// BitSet sBS = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
		// .getGeneBisetForSTBitset(bs);
		// cluster.setCluster(sBS);
		cluster.setCluster(stBitSet);
		return this.addSpeciesBipartitionToX(cluster);
	}

	/**
	 * Adds bipartitions to X. When only one individual from each species is
	 * sampled, this method adds other individuals from that species to the
	 * cluster as well, but note that these additions are done on a copy of c1
	 * not c1 itself.
	 */
	private boolean addSpeciesBipartitionToX(final STITreeCluster stCluster) {
		boolean added = false;

		STITreeCluster c1GT = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
				.getGeneClusterForSTCluster(stCluster);

		added |= this.addCompletedSpeciesFixedBipartionToX(c1GT,
				c1GT.complementaryCluster());

		// if (added) { System.err.print(".");}

		return added;
	}

	/**
	 * Adds extra bipartitions added by user using the option -e and -f
	 */
	public void addExtraBipartitionsByInput(List<Tree> extraTrees,
			boolean extraTreeRooted) {

		// List<Tree> completedExtraGeeneTrees = new ArrayList<Tree>();
		for (Tree tr : extraTrees) {
			String[] gtLeaves = tr.getLeaves();
			STITreeCluster gtAll = GlobalMaps.taxonIdentifier.newCluster();
			for (int i = 0; i < gtLeaves.length; i++) {
				gtAll.addLeaf(GlobalMaps.taxonIdentifier.taxonId(gtLeaves[i]));
			}
			Tree trc = getCompleteTree(tr, gtAll.getBitSet());

			STITree stTrc = new STITree(trc);
			GlobalMaps.taxonNameMap.getSpeciesIdMapper().gtToSt(
					(MutableTree) stTrc);
			if (hasPolytomy(stTrc)) {
				throw new RuntimeException(
						"Extra tree shouldn't have polytomy ");
			}
			ArrayList<Tree> st = new ArrayList<Tree>();
			st.add(stTrc);
			addBipartitionsFromSignleIndTreesToX(stTrc,st, GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier());
		}

	}

	
	public void addBipartitionsByInput(Tree st,
			AbstractInference<Tripartition> inf) {
		
		WQInference inference = (WQInference) inf;
		int haveMissing = preProcess(inference);
		
		String newSpeciesName = inf.getNewSpecies();
		System.err.println("The new species found in the gene trees is: "+newSpeciesName);
	
		List<STITreeCluster> stClusters = Utils.getGeneClusters(st, GlobalMaps.taxonIdentifier);
		
		for(int i= 0; i < GlobalMaps.taxonIdentifier.taxonCount() ; i++){
			STITreeCluster bc = GlobalMaps.taxonIdentifier.newCluster();
			bc.getBitSet().set(i);
			stClusters.add(bc);
			
		}
		
		
		List<STITreeCluster> setX = new ArrayList<STITreeCluster>(stClusters);
//		Set<STITreeCluster> setX = new HashSet<STITreeCluster>();
		int newSpecies = GlobalMaps.taxonIdentifier.taxonId(newSpeciesName);                
		System.err.println("st clusters size:"+ stClusters.size());


		for(int i = 0; i < stClusters.size(); i++){
			STITreeCluster cluster = stClusters.get(i);
//			System.err.println(cluster.getBitSet().cardinality());

			BitSet bs = (BitSet) cluster.getBitSet().clone();
			bs.set(newSpecies);
			STITreeCluster tb = GlobalMaps.taxonIdentifier.newCluster(bs);

			STITreeCluster comp = cluster.complementaryCluster();
			BitSet bsc = (BitSet) comp.getBitSet().clone();

			bsc.flip(newSpecies);		
			STITreeCluster tbc = GlobalMaps.taxonIdentifier.newCluster(bsc);

			setX.add(comp);
			setX.add(tb);
			setX.add(tbc);
		
		}
		
		BitSet a = new BitSet(GlobalMaps.taxonIdentifier.taxonCount());
		a.set(0, GlobalMaps.taxonIdentifier.taxonCount());
		STITreeCluster all = GlobalMaps.taxonIdentifier.newCluster(a);
		addToClusters(all, GlobalMaps.taxonIdentifier.taxonCount());

//		STITreeCluster all = GlobalMaps.taxonIdentifier.newCluster();
//		all.getBitSet().set(0, GlobalMaps.taxonIdentifier.taxonCount());
//		addToClusters(all, GlobalMaps.taxonIdentifier.taxonCount());
		
		for(STITreeCluster cluster : setX){
			addSpeciesBipartitionToX(cluster);
		}
		System.err.println("Search space created");
		System.err.println("Size of search space: "+clusters.getClusterCount());


	}
	

	public boolean hasPolytomy(Tree tr) {
		for (TNode node : tr.postTraverse()) {
			if (node.getChildCount() > 2) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds a bipartition to the set X. This method assumes inputs are already
	 * fixed to have all individuals of the same species.
	 * 
	 * @param c1
	 * @param c2
	 * @return was the cluster new?
	 */
	private boolean addCompletedSpeciesFixedBipartionToX(STITreeCluster c1,
			STITreeCluster c2) {
		boolean added = false;
		int size = c1.getClusterSize();
		/*
		 * TODO: check if this change is correct
		 */
		if (size == GlobalMaps.taxonIdentifier.taxonCount() || size == 0) {
			return false;
		}
		// System.err.println("size:" + size);
		added |= addToClusters(c1, size);
		size = c2.getClusterSize();
		added |= addToClusters(c2, size);
		return added;
	}

	// /***
	// * Computes and adds partitions from the input set (ASTRAL-I)
	// * Also, adds extra bipartitions using ASTRAL-II heuristics.
	// * Takes care of multi-individual dataset subsampling.
	// */
	// @Override
	// public void formSetX(AbstractInference<Tripartition> inf) {
	//
	// WQInference inference = (WQInference) inf;
	// int haveMissing = preProcess(inference);
	// SpeciesMapper spm = GlobalMaps.taxonNameMap.getSpeciesIdMapper();
	//
	// calculateDistances();
	//
	// if (haveMissing > 0 ) {
	// completeGeneTrees();
	// } else {
	// this.completedGeeneTrees = this.originalInompleteGeneTrees;
	// }
	//
	// /*
	// * Calculate gene tree clusters and bipartitions for X
	// */
	// STITreeCluster all = GlobalMaps.taxonIdentifier.newCluster();
	// all.getBitSet().set(0, GlobalMaps.taxonIdentifier.taxonCount());
	// addToClusters(all, GlobalMaps.taxonIdentifier.taxonCount());
	//
	// System.err.println("Building set of clusters (X) from gene trees ");
	//
	//
	// /**
	// * This is where we randomly sample one individual per species
	// * before performing the next steps in construction of the set X.
	// */
	// int maxRepeat =
	// getSamplingRepeationFactor(inference.options.getSamplingrounds());
	//
	// if (maxRepeat > 1)
	// System.err.println("Average  sampling is "+ spm.meanSampling() +
	// ".\nWill do "+maxRepeat+" rounds of sampling ");
	//
	// //System.err.println(this.completedGeeneTrees.get(0));
	// int prev = 0, firstgradiant = -1, gradiant = 0;
	// for (int r = 0; r < maxRepeat; r++) {
	//
	// System.err.println("------------\n"
	// + "Round " +r +" of individual  sampling ...");
	// SingleIndividualSample taxonSample = new
	// SingleIndividualSample(spm,this.geneMatrix);
	//
	// System.err.println("taxon sample " +
	// Arrays.toString(taxonSample.getTaxonIdentifier().getAllTaxonNames()));
	//
	// List<Tree> contractedTrees =
	// taxonSample.contractTrees(this.completedGeeneTrees);
	//
	// //System.err.println(trees.get(0));
	//
	// addBipartitionsFromSignleIndTreesToX(contractedTrees, taxonSample);
	//
	// System.err.println("Number of clusters after simple addition from gene trees: "
	// + clusters.getClusterCount());
	//
	// if (inference.getAddExtra() != 0) {
	// System.err.println("calculating extra bipartitions to be added at level "
	// + inference.getAddExtra() +" ...");
	// this.addExtraBipartitionByHeuristics(contractedTrees, taxonSample);
	//
	// System.err.println("Number of Clusters after addition by greedy: " +
	// clusters.getClusterCount());
	// gradiant = clusters.getClusterCount() - prev;
	// prev = clusters.getClusterCount();
	// if (firstgradiant == -1)
	// firstgradiant = gradiant;
	// else {
	// //System.err.println("First gradiant: " + firstgradiant+
	// " current gradiant: " + gradiant);
	// if (gradiant < firstgradiant / 10) {
	// //break;
	// }
	// }
	//
	// }
	// }
	// System.err.println();
	//
	// System.err.println("Number of Default Clusters: " +
	// clusters.getClusterCount());
	//
	// }

	/***
	 * Computes and adds partitions from the input set (ASTRAL-I) Also, adds
	 * extra bipartitions using ASTRAL-II heuristics. Takes care of
	 * multi-individual dataset subsampling.
	 */
	@Override
	public void formSetX(AbstractInference<Tripartition> inf) {


		WQInference inference = (WQInference) inf;
		int haveMissing = preProcess(inference);
		SpeciesMapper spm = GlobalMaps.taxonNameMap.getSpeciesIdMapper();

		calculateDistances();
		if (haveMissing > 0) {
			completeGeneTrees();
		} else {
			this.completedGeeneTrees = new ArrayList<Tree>(
					this.originalInompleteGeneTrees.size());

			for (Tree t : this.originalInompleteGeneTrees) {
				this.completedGeeneTrees.add(new STITree(t));
			}
		}

		System.err.println("Building set of clusters (X) from gene trees ");

		Logging.logTimeMessage(" WQDataCollection 558-561: ");

		/**
		 * This is where we randomly sample one individual per species before
		 * performing the next steps in construction of the set X.
		 */
		//int firstRoundSampling = 400;

		int secondRoundSampling = getSamplingRepeationFactor(inference.options.getSamplingrounds());;


		ArrayList<SingleIndividualSample> firstRoundSamples = new ArrayList<SingleIndividualSample>();
		int K = 100;
		STITreeCluster all = GlobalMaps.taxonIdentifier.newCluster();
		all.getBitSet().set(0, GlobalMaps.taxonIdentifier.taxonCount());
		addToClusters(all, GlobalMaps.taxonIdentifier.taxonCount());

		int arraySize = this.completedGeeneTrees.size();
		List<Tree>[] allGreedies = new List[arraySize];

		//int prev = 0;
		Logging.logTimeMessage(" WQDataCollection 588-591: ");



		if (GlobalMaps.taxonNameMap.getSpeciesIdMapper().isSingleIndividual()) {
			int gtindex = 0;
			for (Tree gt : this.completedGeeneTrees) {
				ArrayList<Tree> tmp = new ArrayList<Tree>();
				STITree gtrelabelled = new STITree(gt);
				GlobalMaps.taxonNameMap.getSpeciesIdMapper().gtToSt(
						(MutableTree) gtrelabelled);
				tmp.add(gtrelabelled);
				allGreedies[gtindex++] = tmp;
			}
		} else {
			/*
			 * instantiate k random samples
			 */

			for (int r = 0; r < secondRoundSampling * K; r++) {

				//System.err.println("------------\n" + "sample " + (r+1)
				//	+ " of individual  sampling ...");
				SingleIndividualSample taxonSample = new SingleIndividualSample(
						spm, this.geneMatrix);
				firstRoundSamples.add(taxonSample);

			}

			System.err.println("In second round sampling "
					+ secondRoundSampling + " rounds will be done");
			if (Logging.timerOn) {
				System.err
				.println("TIME TOOK FROM LAST NOTICE WQDataCollection 621-624: "
						+ (double) (System.currentTimeMillis() - Logging.timer) / 1000);
				Logging.timer = System.currentTimeMillis();
			}
			int gtindex = 0;
			for (Tree gt : this.completedGeeneTrees) {
				// System.err.println("gene tree number " + i +
				// " is processing..");
				ArrayList<Tree> firstRoundSampleTrees = new ArrayList<Tree>();

				for (SingleIndividualSample sample : firstRoundSamples) {

					Tree contractedTree = sample.contractTree(gt);
					contractedTree.rerootTreeAtEdge(GlobalMaps.taxonNameMap
							.getSpeciesIdMapper().getSTTaxonIdentifier()
							.getTaxonName(0));
					Trees.removeBinaryNodes((MutableTree) contractedTree);
					// returns a tree with species label
					firstRoundSampleTrees.add(contractedTree);
				}

				ArrayList<Tree> greedies = new ArrayList<Tree>();
				for (int r = 0; r < secondRoundSampling; r++) {
					List<Tree> sample;

					// Collections.shuffle(firstRoundSampleTrees,
					// GlobalMaps.random);
					sample = firstRoundSampleTrees.subList(r * K, K * r + 99);
					greedies.add(Utils.greedyConsensus(sample, false,
							GlobalMaps.taxonNameMap.getSpeciesIdMapper()
							.getSTTaxonIdentifier(), true));
				}

				allGreedies[gtindex++] = greedies;
				// System.err.println("Number of clusters after simple addition from gene trees: "
				// + clusters.getClusterCount());

			}
			Logging.logTimeMessage("WQDataCollection 657-660: ");
		}

		/**
		 * generate a list of sampled gene trees selecting each one randomly
		 */

		ArrayList<Tree> baseTrees = new ArrayList<Tree>();
		List<STITreeCluster> STls = new ArrayList<STITreeCluster>();
		for (BitSet b : this.speciesMatrix.inferTreeBitsets()) {

			STITreeCluster sti = new STITreeCluster(GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier());
			sti.setCluster(b);
			STls.add(sti);
		}
		Tree ST = Utils.buildTreeFromClusters(STls, GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier(), false);
        //		Tree PhyDstar = Utils.buildTreeFromClusters(phyDstar, GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier(), false);
        //		System.err.println(UPGMA.toNewick());
        //        System.err.println();
        //        java.lang.System.exit(0);


		///		Tree allGenesGreedy = Utils.greedyConsensus(greedyCandidates, false,
		//				GlobalMaps.taxonNameMap.getSpeciesIdMapper()
		//						.getSTTaxonIdentifier(), true);
		//		resolveByUPGMA((MutableTree) allGenesGreedy, GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier(),
		//				this.speciesMatrix);


		//	baseTrees.add(allGenesGreedy);
		baseTrees.add(ST);
		addBipartitionsFromSignleIndTreesToX(ST, baseTrees,

				GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier()); 



		Logging.logTimeMessage(" WQDataCollection 701-704: ");

		CountDownLatch latch = new CountDownLatch(secondRoundSampling*allGreedies.length);
		for (int ii = 0; ii < secondRoundSampling; ii++) {
			for (int j = 0; j < allGreedies.length; j++) {
				//ArrayList<Tree> baseTreesCopy = new ArrayList<Tree>(baseTrees);
				Threading.execute(new FormSetXLoop(allGreedies[j].get(ii), baseTrees, latch));

			}			
			System.err.println("------------------------------");
			//gradiant = clusters.getClusterCount() - prev;
			//System.err.println("gradient" + ii + ": " + gradiant);
			//prev = clusters.getClusterCount();

		}
		try {
			latch.await();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		Logging.logTimeMessage(" WQDataCollection 728-731: ");

		//prev = 0;

		//gradiant = 0;
		if (inference.getAddExtra() == 0) {
			return;
		}
		
		this.addExtraBipartitionByDistance();

		System.err.println("Adding to X using resolutions of greedy consensus ...\n");

		for (int l = 0; l < secondRoundSampling; l++) {
			ArrayList<Tree> genes = new ArrayList<Tree>();
			for (int j = 0; j < allGreedies.length; j++) {
				genes.add(allGreedies[j].get(l));
			}
			
			this.addExtraBipartitionByHeuristics(genes,
					GlobalMaps.taxonNameMap.getSpeciesIdMapper()
					.getSTTaxonIdentifier(),inference.options.getPolylimit());

			//gradiant = clusters.getClusterCount() - prev;
			//prev = clusters.getClusterCount();

		}
		
		System.err.println("Number of Clusters after addition by greedy: "+clusters.getClusterCount());
		Logging.logTimeMessage(" WQDataCollection 760-763: ");

	}

	public class FormSetXLoop implements Runnable {
		Tree tree;
		ArrayList<Tree> baseTrees;

		CountDownLatch latch;

		public FormSetXLoop(Tree tree,
				ArrayList<Tree> baseTrees, CountDownLatch latch) {
			this.tree = tree;
			this.baseTrees = baseTrees;
			this.latch = latch;
		}

		public void run() {
			try {
				addBipartitionsFromSignleIndTreesToX(tree, baseTrees, GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier());
			} catch (Exception e) {
				e.printStackTrace();
			}

			latch.countDown();
		}

	}

	/**
	 * Calculates a distance matrix based on input gene trees. To be used for
	 * gene tree completion.
	 */
	private void calculateDistances() {

        if (options.isUstarDist()) {
            // Note that matricesByBranchDistance both populates the similarity matrix and returns the speces level matrix. 
            this.geneMatrix = new DistanceMatrix(GlobalMaps.taxonIdentifier.taxonCount());

        } else {
            this.geneMatrix = new SimilarityMatrix(GlobalMaps.taxonIdentifier.taxonCount());
        }

		System.err
		.print("Calculating distance matrix (for completion of X) ....");
        this.speciesMatrix = this.geneMatrix.populate( treeAllClusters, 
                this.originalInompleteGeneTrees,
                GlobalMaps.taxonNameMap.getSpeciesIdMapper());	
        System.err.println();
        
    }

	/**
	 * Computes the set of available leaves per gene tree.
	 * 
	 * @param inference
	 * @return
	 */
	int preProcess(AbstractInference<Tripartition> inference) {
		System.err.println("Number of gene trees: "
				+ this.originalInompleteGeneTrees.size());
		// n = GlobalMaps.taxonIdentifier.taxonCount();

		int haveMissing = 0;
		for (Tree tree : this.originalInompleteGeneTrees) {
			if (tree.getLeafCount() != GlobalMaps.taxonIdentifier.taxonCount()) {
				haveMissing++;
			}
			reroot(tree);
			Stack<STITreeCluster> stack = new Stack<STITreeCluster>();
			for (TNode n: tree.postTraverse()) {
				STINode node = (STINode) n;
				if (node.isLeaf()) {
					String nodeName = node.getName(); //GlobalMaps.TaxonNameMap.getSpeciesName(node.getName());

					STITreeCluster cluster = GlobalMaps.taxonIdentifier.newCluster();
					Integer taxonID = GlobalMaps.taxonIdentifier.taxonId(nodeName);
					cluster.addLeaf(taxonID);

					stack.add(cluster);
					node.setData(cluster);

				} else {
					ArrayList<STITreeCluster> childbslist = new ArrayList<STITreeCluster>();
					BitSet bs = new BitSet(GlobalMaps.taxonIdentifier.taxonCount());
					for (TNode child: n.getChildren()) {
						STITreeCluster pop = stack.pop();
						childbslist.add(pop);
						bs.or(pop.getBitSet());
					}

					STITreeCluster cluster = GlobalMaps.taxonIdentifier.newCluster((BitSet) bs.clone());

					//((STINode)node).setData(new GeneTreeBitset(node.isRoot()? -2: -1));
					stack.add(cluster);
					node.setData(cluster);
				}
			}
			String[] gtLeaves = tree.getLeaves();
			STITreeCluster gtAll = GlobalMaps.taxonIdentifier.newCluster();
			long ni = gtLeaves.length;
			for (int i = 0; i < ni; i++) {
				gtAll.addLeaf(GlobalMaps.taxonIdentifier.taxonId(gtLeaves[i]));
			}
			treeAllClusters.add(gtAll);
		}
		System.err.println(haveMissing + " trees have missing taxa");

		
		return haveMissing;
	}

	private void reroot(Tree tr) {
		List<STINode> children = new ArrayList<STINode>();
		int n = tr.getLeafCount()/2;
		int dist = n;
		TNode newroot = tr.getRoot();
		for (TNode node : tr.postTraverse()) {
			if (!node.isLeaf()) {                        
				for (TNode child : node.getChildren()) {
					if (child.isLeaf()) {
						children.add((STINode) child);
						break;
					}
				}
				if (Math.abs(n - node.getLeafCount()) < dist) {
					newroot = node;
					dist = n - node.getLeafCount();
				}
			}
		}
		for (STINode child: children) {
			STINode snode = child.getParent();
			snode.removeChild((TMutableNode) child, false);
			TMutableNode newChild = snode.createChild(child);
			if (child == newroot) {
				newroot = newChild;
			}
		}
		if (newroot != tr.getRoot())
			((STITree)(tr)).rerootTreeAtEdge(newroot);

		
	}

	/*
	 * long maxPossibleScore(Tripartition trip) {
	 * 
	 * long weight = 0;
	 * 
	 * for (STITreeCluster all : this.treeAllClusters){ long a =
	 * trip.cluster1.getBitSet().intersectionSize(all.getBitSet()), b =
	 * trip.cluster2.getBitSet().intersectionSize(all.getBitSet()), c =
	 * trip.cluster3.getBitSet().intersectionSize(all.getBitSet());
	 * 
	 * weight += (a+b+c-3)*a*b*c; } return weight; }
	 */

	/**
	 * Completes all the gene trees using a heuristic algorithm described in
	 * Siavash's dissertation. Uses the distance matrix for completion.
	 */
	private void completeGeneTrees() {
		System.err
		.println("Will attempt to complete bipartitions from X before adding using a distance matrix.");
		int t = 0;
		BufferedWriter completedFile = null;
		if (this.options.isOutputCompletedGenes()) {
			String fn = this.options.getOutputFile() + ".completed_gene_trees";
			System.err.println("Outputting completed gene trees to " + fn);
			try {
				completedFile = new BufferedWriter(new FileWriter(fn));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		for (Tree tr : this.originalInompleteGeneTrees) {
			Tree trc = getCompleteTree(tr, this.treeAllClusters.get(t++)
					.getBitSet());
			this.completedGeeneTrees.add(trc);
			if (completedFile != null) {
				try {
					completedFile.write(trc.toStringWD() + " \n");
					completedFile.flush();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		if (completedFile != null) {
			try {
				completedFile.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * for debugging
	 * 
	 * @param distSTMatrix
	 */
	private void printoutdistmatrix(double[][] distSTMatrix) {
		SpeciesMapper spm = GlobalMaps.taxonNameMap.getSpeciesIdMapper();
		for (String s : spm.getSTTaxonIdentifier().getAllTaxonNames()) {
			System.err.print(String.format("%1$8s", s));
		}
		System.err.println();
		for (int i = 0; i < spm.getSpeciesCount(); i++) {
			for (int j = 0; j < spm.getSpeciesCount(); j++) {
				System.err.print(String.format("%1$8.3f", distSTMatrix[i][j]));
			}
			System.err.println();
		}
	}

	/**
	 * By default (when SLOW is false) it only computes an UPGMA tree from the
	 * distance data and adds to the set of bipartitions
	 */
	public void addExtraBipartitionByDistance() {


        for (BitSet bs : speciesMatrix.inferTreeBitsets()) {
			STITreeCluster g = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
					.getGeneClusterForSTCluster(bs);
			this.addCompletedSpeciesFixedBipartionToX(g,
					g.complementaryCluster());
		}

		if (SLOW) {
			for (BitSet bs : speciesMatrix.getQuadraticBitsets()) {
				STITreeCluster g = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
						.getGeneClusterForSTCluster(bs);
				this.addCompletedSpeciesFixedBipartionToX(g,
						g.complementaryCluster());
			}
		}

		System.err.println("Number of Clusters after addition by distance: "
				+ clusters.getClusterCount());
	}

	/**
	 * Main function implementing new heuristics in ASTRAL-II. At this point, we
	 * require a subsample with a single individual per species.
	 * 
	 * @param trees
	 *            : the input trees contracted to the subsample
	 * @param sis
	 *            : the single-individual subsample information
	 */
	void addExtraBipartitionByHeuristics(Collection<Tree> contractedTrees,
			TaxonIdentifier tid, int polylimit) {

		// Greedy trees. These will be based on sis taxon identifier
		Collection<Tree> allGreedies;
		
		System.err.print("Computing greedy consensus  ");
		long t = System.currentTimeMillis();
		for (Tree tree : contractedTrees) {
			tree.rerootTreeAtEdge(tid.getTaxonName(0));
			Trees.removeBinaryNodes((MutableTree) tree);
		}

		/*
		 * if (completeTrees.size() < 2) {
		 * System.err.println("Only "+completeTrees.size() +
		 * " complete trees found. Greedy-based completion not applicable.");
		 * return; }
		 */
		allGreedies = Utils.greedyConsensus(contractedTrees,
				this.GREEDY_ADDITION_THRESHOLDS, true, 1, tid, true);
		int sumDegrees = 0;

		System.err.println("took "+ ((System.currentTimeMillis()-t)/1000+" seconds"));

		ArrayList<Integer> deg = new ArrayList<Integer>();
		for (Tree cons : allGreedies) {
			for (TNode greedyNode : cons.postTraverse()) {
				if (greedyNode.getChildCount() > 2) {
					deg.add(greedyNode.getChildCount());
				}
			}
		}
		Collections.sort(deg);

		if(polylimit == -1){
			// System.err.println(deg);
			int N = this.GREEDY_ADDITION_MAX_POLYTOMY_MIN
					+ GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSpeciesCount()
					* this.GREEDY_ADDITION_MAX_POLYTOMY_MULT;
			System.err.println("Limit for sigma of degrees:" + N + "\n");
			int i = 0;
			while (sumDegrees < N && i < deg.size()) {
				sumDegrees += Math.pow(deg.get(i), 2);
				i++;
			}

			if(i > 0)
				polytomySizeLimit = deg.get(i-1);
			else    
				polytomySizeLimit = 3; // this means that the tree is fully binary

		}
		else
			polytomySizeLimit = polylimit;

		// if(i==deg.size())
		// allDegVisitedMaxDegrees.add(deg.get(i-1));
		// else
		// allDegNotVisitedMaxDegrees.add(deg.get(i-1));
		//
		// // System.err.println("max degree: "+ maxDegrees[j]+" i: "+i+
		// "  deg size: "+ deg.size());
		// // if((maxDegree < polytomySizeLimit && i != deg.size()) ||
		// polytomySizeLimit == POLYTOMY_SIZE_LIMIT_MAX)
		// // polytomySizeLimit = maxDegree;
		//
		// System.err.println(allDegNotVisitedMaxDegrees);
		// System.err.println(allDegVisitedMaxDegrees);
		// polytomySizeLimit = Math.max(arrayListMax(allDegVisitedMaxDegrees),
		// arrayListMin(allDegNotVisitedMaxDegrees));

		System.err.println("polytomy size limit : > " + polytomySizeLimit);
		System.err.println(" " + deg );
		System.err.println("discarded polytomies: ");
		for (int d : deg) {
			if (d > polytomySizeLimit)
				System.err.println(d);
		}

		Object lock = new Object();
		int th = 0;
		//Integer max = 0;
		/**
		 * For each greedy consensus tree, use it to add extra bipartitions to
		 * the tree.
		 */

		ArrayList stringOutput = new ArrayList();
		for (Tree cons : allGreedies) {
			double thresh = this.GREEDY_ADDITION_THRESHOLDS[th];
			stringOutput.add("Threshold " + thresh + ":" + "\n");

			for (TNode greedyNode : cons.postTraverse()) {

				if (greedyNode.isLeaf() || greedyNode.getChildCount() <= 2) {
					continue;
				}

				//System.err.println("Queued: " + "polytomy of size " + greedyNode.getChildCount());

				stringOutput.add(Threading.submit(new addExtraBipartitionByHeuristicsLoop(
								greedyNode, tid, th, contractedTrees,
								lock)));

			}
			th = (th + 1) % this.GREEDY_ADDITION_THRESHOLDS.length;
		}
		
		for(int i = 0; i < stringOutput.size(); i++) {
			if(stringOutput.get(i) instanceof String) {
				System.err.print(stringOutput.get(i));
			} else {
				try {
					System.err.print(((Future<String>)stringOutput.get(i)).get());
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		//stringOutput.add("max k is :" + max + "\n");
	}

	public class addExtraBipartitionByHeuristicsLoop implements Callable {
		TNode greedyNode;
		TaxonIdentifier tid;
		int th;
		Collection<Tree> contractedTrees;
		Object lock;

		public addExtraBipartitionByHeuristicsLoop(TNode greedyNode,
				TaxonIdentifier tid, int th,
				Collection<Tree> contractedTrees, 
				Object lock) {
			this.greedyNode = greedyNode;
			this.tid = tid;
			this.th = th;
			this.contractedTrees = contractedTrees;
			this.lock = lock;
		}

		public String call() {

			String ret;
			BitSet greedyBS = (BitSet) ((STITreeCluster) ((STINode) greedyNode)
					.getData()).getBitSet();

			BitSet[] childbs = new BitSet[greedyNode.getChildCount() + 1];
			int i1 = 0;
			for (TNode c : greedyNode.getChildren()) {
				childbs[i1] = (BitSet) ((STITreeCluster) ((STINode) c)
						.getData()).getBitSet();
				i1++;
			}
			// Compute the complementary cluster of the cluster for this
			// node
			// recall: a greedy consensus is defined on unrooted trees and
			// we are treating the tree as rooted here. Computing
			// the complementary cluster and adding it to the child list
			// in effect makes the tree unrooted again
			BitSet comp = (BitSet) greedyBS.clone();
			comp.flip(0, tid.taxonCount());
			childbs[i1] = comp;

			ret = "polytomy of size " + greedyNode.getChildCount();

			// First resolve the polytomy using distances.

			WQDataCollection.this.addSubSampledBitSetToX(
					WQDataCollection.this.speciesMatrix.resolvePolytomy(
							Arrays.asList(childbs), true), tid);

			// Resolve by subsampling the greedy.
			// Don't get confused. We are not subsampling species
			// in a greedy consensus tree, which itself, subsamples one
			// individual per species.
			int k = 0;

			for (int j = 0; j < GREEDY_ADDITION_DEFAULT_RUNS + k; j++) {

				boolean quadratic = (SLOW
						|| (th < GREEDY_DIST_ADDITTION_LAST_THRESHOLD_INDX && j < GREEDY_ADDITION_DEFAULT_RUNS)) 
								&& greedyNode.getChildCount() <= polytomySizeLimit;


				if (sampleAndResolve(childbs, contractedTrees, quadratic, tid, true, false) && k < GREEDY_ADDITION_MAX) {
					k += GREEDY_ADDITION_IMPROVEMENT_REWARD;
		
				}
			}
			ret += "; rounds with additions with at least "
					+ GREEDY_ADDITION_MIN_FREQ + " support: " + k
					/ GREEDY_ADDITION_IMPROVEMENT_REWARD + "; clusters: "
					+ clusters.getClusterCount() + "\n";
			return ret;
		}
	}

	int arrayListMax(ArrayList<Integer> input) {
		if (input.size() == 0)
			return 0;
		else
			return Collections.max(input);
	}

	int arrayListMin(ArrayList<Integer> input) {
		if (input.size() == 0)
			return 0;
		else
			return Collections.min(input);
	}

	/*
	private boolean resolveByUPGMA(BitSet[] polytomyBSList,
			SingleIndividualSample sis, TaxonIdentifier id) {
		return this.addSubSampledBitSetToX(sis.getMatrix()
				.resolveByUPGMA(Arrays.asList(polytomyBSList), true), id);
	}*/

	/**
	 * This is the first step of the greedy algorithm where one counts how many
	 * times a bitset is present in input gene trees. A complication is that
	 * this is computing the greedy consensus among the gene trees subsampled to
	 * the given randomSample
	 * 
	 * @param genetrees
	 * @param randomSample
	 * @return
	 */
	private HashMap<BitSet, Integer> returnBitSetCounts(
			Collection<Tree> genetrees, HashMap<String, Integer> randomSample) {

		HashMap<BitSet, Integer> counts = new HashMap<BitSet, Integer>();

		for (Tree gt : genetrees) {
			List<BitSet> bsList = Utils.getBitsets(randomSample, gt);

			for (BitSet bs : bsList) {
				if (counts.containsKey(bs)) {
					counts.put(bs, counts.get(bs) + 1);
					continue;
				}
				BitSet bs2 = (BitSet) bs.clone();
				bs2.flip(0, randomSample.size());
				if (counts.containsKey(bs2)) {
					counts.put(bs2, counts.get(bs2) + 1);
					continue;
				}
				counts.put(bs, 1);
			}
		}
		return counts;
	}

	/**
	 * For a given polytomy, samples randomly around its branches and adds
	 * results to the set X.
	 * 
	 * @param polytomyBSList
	 * @param addQuadratic
	 * @return Whether any clusters of high frequency were added in this round
	 */
	private boolean sampleAndResolve(BitSet[] polytomyBSList,
			Collection<Tree> inputTrees, boolean addQuadratic,
			TaxonIdentifier tid, boolean addByDistance,
			boolean forceResolution) {

		boolean addedHighFreq = false;
		// random sample taxa
		HashMap<String, Integer> randomSample = randomSampleAroundPolytomy(
				polytomyBSList, tid);

		addedHighFreq = resolveLinearly(polytomyBSList, inputTrees,
				randomSample, tid, forceResolution);
		if (addByDistance)
			resolveByDistance(polytomyBSList, randomSample, addQuadratic,
					tid);

		return addedHighFreq;
	}

	/**
	 * Resolves a polytomy using the greedy consensus of a subsample from
	 * clusters around it
	 * 
	 * @param polytomyBSList
	 * @param randomSample
	 * @return
	 */
	private boolean resolveLinearly(BitSet[] polytomyBSList,
			Collection<Tree> inputTrees, HashMap<String, Integer> randomSample,
			TaxonIdentifier tid, boolean forceresolution) {
		int sampleSize = randomSample.size();
		// get bipartition counts in the induced trees//******************************************		
		HashMap<BitSet, Integer> counts = returnBitSetCounts(
				inputTrees, randomSample);


		// sort bipartitions
		TreeSet<Entry<BitSet, Integer>> countSorted = new TreeSet<Entry<BitSet, Integer>>(
				new Utils.BSComparator(true, sampleSize));
		countSorted.addAll(counts.entrySet());

		// build the greedy tree
		MutableTree greedyTree = new STITree<BitSet>();
		TNode[] tmpnodes = new TNode[sampleSize];
		for (int i = 0; i < sampleSize; i++) {
			tmpnodes[i] = greedyTree.getRoot().createChild(i + "");
			BitSet bs = new BitSet(sampleSize);
			bs.set(i);
			((STINode<BitSet>) tmpnodes[i]).setData(bs);
		}

		boolean added = false;
		boolean addedHighFreq = false;
		List<BitSet> newBSList = new ArrayList<BitSet>();

		for (Entry<BitSet, Integer> entry : countSorted) {

			BitSet newbs = entry.getKey();

			SchieberVishkinLCA lcaFinder = new SchieberVishkinLCA(greedyTree);
			Set<TNode> clusterLeaves = new HashSet<TNode>();
			TNode node;
			for (int i = newbs.nextSetBit(0); i >= 0; i = newbs
					.nextSetBit(i + 1)) {
				node = tmpnodes[i];
				clusterLeaves.add(node);
			}
			TNode lca = lcaFinder.getLCA(clusterLeaves);
			LinkedList<TNode> movedChildren = new LinkedList<TNode>();
			int nodes = clusterLeaves.size();
			for (TNode child : lca.getChildren()) {
				BitSet childCluster = ((STINode<BitSet>) child).getData();

				BitSet temp = (BitSet) childCluster.clone();
				temp.and(newbs);
				if (temp.equals(childCluster)) {
					movedChildren.add(child);
					nodes -= temp.cardinality();
				}

			}

			// boolean isPartOfGreedy = false;
			if (movedChildren.size() != 0 && nodes == 0) {

				STINode<BitSet> newChild = ((STINode<BitSet>) lca)
						.createChild();
				newChild.setData(newbs);

				while (!movedChildren.isEmpty()) {
					newChild.adoptChild((TMutableNode) movedChildren.get(0));
					movedChildren.remove(0);
				}

				if (addDoubleSubSampledBitSetToX(polytomyBSList, newbs, tid)) {
					if (GREEDY_ADDITION_MIN_RATIO <= (entry.getValue() + 0.0)
							/ inputTrees.size()
							&& entry.getValue() > GREEDY_ADDITION_MIN_FREQ) {
						addedHighFreq = true;
					}
					added = true;
				}

				/*
				 * if ((GREEDY_ADDITION_MIN_FREQ <=
				 * (entry.getValue()+0.0)/this.completedGeeneTrees.size()) &&
				 * spm.isPerfectGTBitSet(newbs)) { if
				 * (this.addSingleIndividualBitSetToX(newbs)){ addedHighFreq =
				 * true; added = true; } } else{ newBSList.add(newbs); }
				 */
			}

		}

		if (forceresolution || added) {
			for (TNode node : greedyTree.postTraverse()) {
				if (node.getChildCount() < 3) {
					continue;
				}
				ArrayList<BitSet> children = new ArrayList<BitSet>(
						node.getChildCount() + 1);
				BitSet rest = new BitSet(sampleSize);
				for (TNode child : node.getChildren()) {
					children.add(((STINode<BitSet>) child).getData());
					rest.or(((STINode<BitSet>) child).getData());
				}
				rest.flip(0, sampleSize);
				if (rest.cardinality() != 0)
					children.add(rest);

				// addSubSampledBitSetToX(polytomyBSList,
				// this.geneMatrix.resolveByUPGMA(children, true));
				// //TODO: addback

				while (children.size() > 2) {
					BitSet c1 = children.remove(GlobalMaps.random
							.nextInt(children.size()));
					BitSet c2 = children.remove(GlobalMaps.random
							.nextInt(children.size()));

					BitSet newbs = (BitSet) c1.clone();
					newbs.or(c2);
					addDoubleSubSampledBitSetToX(polytomyBSList, newbs, tid);
					children.add(newbs);
				}
			}
		}

		// this.addSubSampledBitSetToX(polytomyBSList, newBSList);

		return addedHighFreq;
	}

	private boolean resolveByDistance(BitSet[] polytomyBSList,
			HashMap<String, Integer> randomSample, boolean quartetAddition,
			TaxonIdentifier tid) {
		boolean added = false;

		Matrix sampleSimMatrix = this.speciesMatrix.getInducedMatrix(randomSample,tid);

		added |= this.addDoubleSubSampledBitSetToX(polytomyBSList,
				sampleSimMatrix.inferTreeBitsets(), tid);

		if (quartetAddition) {
			added |= this.addDoubleSubSampledBitSetToX(polytomyBSList,
					sampleSimMatrix.getQuadraticBitsets(), tid);
		}
		return added;
	}

	private HashMap<String, Integer> randomSampleAroundPolytomy(
			BitSet[] polyTomy, TaxonIdentifier id) {
		HashMap<String, Integer> randomSample = new HashMap<String, Integer>();
		int ind = 0;
		for (BitSet child : polyTomy) {
			int sample = GlobalMaps.random.nextInt(child.cardinality());
			int p = child.nextSetBit(0);
			for (int i = 0; i < sample; i++) {
				p = child.nextSetBit(p + 1);
			}
			randomSample.put(id.getTaxonName(p), ind);
			ind++;
		}
		return randomSample;
	}

	private boolean addDoubleSubSampledBitSetToX(BitSet[] childbs,
			BitSet restrictedBitSet, TaxonIdentifier tid) {
		BitSet stnewBS = addbackAfterSampling(childbs, restrictedBitSet, tid);
		return this.addSpeciesBitSetToX(stnewBS);
	}

	private boolean addSubSampledBitSetToX(
			Iterable<BitSet> restrictedBitSetList, TaxonIdentifier tid) {
		boolean added = false;
		for (BitSet restrictedBitSet : restrictedBitSetList) {
			added |= this.addSpeciesBitSetToX(restrictedBitSet);
		}
		return added;
	}

	private boolean addDoubleSubSampledBitSetToX(BitSet[] childbs,
			Iterable<BitSet> restrictedBitSetList, TaxonIdentifier tid) {
		boolean addded = false;
		for (BitSet restrictedBitSet : restrictedBitSetList) {
			addded |= addDoubleSubSampledBitSetToX(childbs, restrictedBitSet,
					tid);
		}
		return addded;
	}

	private BitSet addbackAfterSampling(BitSet[] childbs,
			BitSet restrictedBitSet, TaxonIdentifier tid) {
		BitSet newbs = new BitSet(tid.taxonCount());
		for (int j = restrictedBitSet.nextSetBit(0); j >= 0; j = restrictedBitSet
				.nextSetBit(j + 1)) {
			newbs.or(childbs[j]);
		}
		return newbs;
	}

	// private void resolveByUPGMA(MutableTree tree, SingleIndividualSample
	// sample) {
	// Stack<BitSet> stack = new Stack<BitSet>();
	// for (TNode node : tree.postTraverse()) {
	// BitSet bitset = new BitSet(sample.getTaxonIdentifier().taxonCount());
	// if (node.isLeaf()) {
	// bitset.set(sample.getTaxonIdentifier().taxonId(node.getName()));
	// } else {
	// List<TMutableNode> children = new ArrayList<TMutableNode>();
	// ArrayList<BitSet> poly = new ArrayList<BitSet> ();
	// for (TNode child : node.getChildren()) {
	// BitSet cbs = stack.pop();
	// poly.add(cbs);
	// children.add((TMutableNode) child);
	// bitset.or(cbs);
	// }
	// if (children.size() > 2) {
	//
	// for (BitSet bs: sample.getMatrix().resolveByUPGMA(poly,false))
	// {
	// TMutableNode newChild = ((TMutableNode)node).createChild();
	// for(int i = bs.nextSetBit(0); i >=0; i = bs.nextSetBit(i+1) ) {
	// TMutableNode child = children.get(i);
	// if (child.getParent() == node) {
	// newChild.adoptChild(child);
	// }
	// children.set(i, newChild);
	// }
	// }
	// }
	// }
	// stack.push(bitset);
	// }
	// }

	/*private void resolveByUPGMA(MutableTree tree, TaxonIdentifier ti,
			Matrix sm) {
		Stack<BitSet> stack = new Stack<BitSet>();
		for (TNode node : tree.postTraverse()) {
			BitSet bitset = new BitSet(ti.taxonCount());
			if (node.isLeaf()) {
				bitset.set(ti.taxonId(node.getName()));
			} else {
				List<TMutableNode> children = new ArrayList<TMutableNode>();
				ArrayList<BitSet> poly = new ArrayList<BitSet>();
				for (TNode child : node.getChildren()) {
					BitSet cbs = stack.pop();
					poly.add(cbs);
					children.add((TMutableNode) child);
					bitset.or(cbs);

				}
				if (children.size() > 2) {
					for (BitSet bs : sm.resolveByUPGMA(poly, false)) {
						TMutableNode newChild = ((TMutableNode) node)
								.createChild();
						for (int i = bs.nextSetBit(0); i >= 0; i = bs
								.nextSetBit(i + 1)) {
							TMutableNode child = children.get(i);
							if (child.getParent() == node) {
								newChild.adoptChild(child);
							}
							children.set(i, newChild);
						}
					}
				}
			}
			stack.push(bitset);
		}
	}*/

	public Object clone() throws CloneNotSupportedException {
		WQDataCollection clone = (WQDataCollection) super.clone();
		clone.clusters = (WQClusterCollection) ((AbstractClusterCollection) this.clusters)
				.clone();
		return clone;
	}

	public long[] getAllArray() {
		int counter = 0;
		int wordLength =  (GlobalMaps.taxonIdentifier.taxonCount() / 64 + 1);
		long[] allArray = new long[this.treeAllClusters.size() * wordLength];
		for (int i = 0; i < this.treeAllClusters.size(); i++) {
			for (int j = wordLength - 1; j >= 0; j--)
				allArray[counter++] = this.treeAllClusters
						.get(i).getBitSet().words[j];
		}
		return allArray;
	}
	
	
	/*
	 * private BitSet hemogenizeBipartitionByVoting(BitSet b1copy, BitSet
	 * b2copy) {
	 * 
	 * Find out for each species whether they are more frequent in left or right
	 * 
	 * int [] countsC1c = new int [spm.getSpeciesCount()], countsC2c = new int
	 * [spm.getSpeciesCount()]; for (int i = b1copy.nextSetBit(0); i >=0 ; i =
	 * b1copy.nextSetBit(i+1)) { int sID = spm.getSpeciesIdForTaxon(i);
	 * countsC1c[sID]+=10; if (spm.getLowestIndexIndividual(sID) == i ) {
	 * countsC1c[sID]++; } } for (int i = b2copy.nextSetBit(0); i >=0 ; i =
	 * b2copy.nextSetBit(i+1)) { int sID = spm.getSpeciesIdForTaxon(i);
	 * countsC2c[sID]+=10; if (spm.getLowestIndexIndividual(sID) == i ) {
	 * countsC2c[sID]++; } }
	 *//**
	 * Add a bipartition where every individual is moved to the side where it
	 * is more common
	 */
	/*
	 * BitSet gtbs1 = new BitSet(spm.getSpeciesCount()); for (int i = 0; i <
	 * countsC2c.length; i++) { if (countsC1c[i] > countsC2c[i]) { gtbs1.set(i);
	 * } } return gtbs1; }
	 */
}
