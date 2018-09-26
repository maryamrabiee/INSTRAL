package phylonet.coalescent;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import phylonet.tree.model.TNode;
import phylonet.tree.model.Tree;
import phylonet.tree.model.sti.STINode;
import phylonet.tree.model.sti.STITreeCluster;
import phylonet.util.BitSet;

/**
 * Implements a Distance method
 * @author smirarab
 *
 */
public class SimilarityMatrix extends AbstractMatrix implements Matrix {


	public SimilarityMatrix(int n) {
		this.n = n;
	}

	public SimilarityMatrix(float[][] from) {
		this.n = from.length;
        this.matrix = from;
        /*this.matrix = new float[from.length][from[0].length];
		for (int i = 0; i < from.length; i++) {
			float[] l = from[i];
			for (int j = 0; j < l.length; j++) {
				this.matrix[i][j] = l[j];
			}
		}*/
	}
	
    int compareTwoValues(float f1, float f2) {
        int vc = Float.compare(f1,f2);
        return - vc;
    }

    public int getBetterSideByFourPoint(int x, int a, int b, int c) {
        double xa = this.matrix[x][a];
        double xb = this.matrix[x][b];
        double xc = this.matrix[x][c];
        double ab = this.matrix[a][b];
        double ac = this.matrix[a][c];
        double bc = this.matrix[b][c];
        double ascore = xa + bc  - (xb + ac); // Note this is similartiy, not distance
        double bscore = xb + ac  - (xa + bc); 
        double cscore = xc + ab - (xb + ac); 
        return ascore >= bscore ?
                ascore >= cscore ? a : c :
                    bscore >= cscore ? b : c;	
    }


	private void updateQuartetDistanceTri(BitSet left,
			BitSet right, float[][] matrix, double d) {
		if (d == 0)
			return;
		for (int l = left.nextSetBit(0); l >= 0; l=left.nextSetBit(l+1)) {
			for (int r = right.nextSetBit(0); r >= 0; r=right.nextSetBit(r+1)) {
				matrix[l][r] += d;
				matrix[r][l] = matrix[l][r];
			}
		}
	}

	void populateByQuartetDistance(List<STITreeCluster> treeAllClusters, List<Tree> geneTrees) {
		this.matrix = new float[n][n];
		long [][] denom = new long [n][n];
		Logging.logTimeMessage("SimilarityMatrix 145-148: ");
			
		/*for (Tree tree :  geneTrees) {
			for (TNode node : tree.postTraverse()) {
				if (node.isLeaf()) {
					BitSet tmp = new BitSet(n);
					tmp.set(GlobalMaps.taxonIdentifier.taxonId(node.getName()));
					((STINode)node).setData(tmp);
				} else {

					BitSet newbs = new BitSet(n);
					for (TNode cn: node.getChildren()) {
						BitSet c = (BitSet) ((STINode)cn).getData();
						newbs.or(c);
					}

					((STINode)node).setData(newbs);

				}
			}
		}*/

		ArrayList<Future<float[][][]>> futures = new ArrayList<Future<float[][][]>>();
		for (int i = 0; i < geneTrees.size(); i+=geneTrees.size()/Threading.getNumThreads()) {
			int start = i;
			int end = Math.min(start + geneTrees.size()/Threading.getNumThreads(), geneTrees.size());
			futures.add(Threading.submit(new populateByQuartetDistanceLoop(start, end, treeAllClusters, geneTrees)));
		}
		for (Future future: futures) {
			float[][][] res = null;
			try {
				res = (float[][][])future.get();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			for(int i = 0; i < n; i++) {
				for(int j = 0; j < n; j++) {
					matrix[i][j] += res[0][i][j];
					denom[i][j] += res[1][i][j];
				}
			}
		}
		Logging.logTimeMessage("SimilarityMatrix 161-164: ");
			
		for (int i = 0; i < n; i++) {
			for (int j = i; j < n; j++) {
				if (denom[i][j] == 0)
					matrix[i][j] = 0F;
				else
					matrix[i][j] = matrix[i][j] / (denom[i][j]/2);
				if (i == j) {
					matrix[i][j] = 1F;
				}
				matrix[j][i] = matrix[i][j];
			}
		}
	}
	public class populateByQuartetDistanceLoop implements Callable<float[][][]>{
		List<STITreeCluster> treeAllClusters;
		List<Tree> geneTrees;
		int start;
		int end;
		public populateByQuartetDistanceLoop(int start, int end, List<STITreeCluster> treeAllClusters, List<Tree> geneTrees) {
			this.geneTrees = geneTrees;
			this.start = start;
			this.end = end;
			this.treeAllClusters = treeAllClusters;
		}
		public float[][][] call() {
			float[][] array = new float[n][n];
			float[][] denom = new float[n][n];
			for(int w = start; w < end; w++) {
				STITreeCluster treeallCL = treeAllClusters.get(w);
				Tree tree = geneTrees.get(w);
				Integer treeall = treeallCL.getClusterSize();

				for (TNode node : tree.postTraverse()) {
					if (node.isLeaf()) { 
						continue;
					}
					BitSet cluster = (BitSet) ((STITreeCluster) ((STINode)node).getData()).getBitSet();
					BitSet others = (BitSet) treeallCL.getBitSet().clone();
					others.andNot(cluster);
					ArrayList<BitSet> children = new ArrayList<BitSet>();
					long totalPairs = 0;
					long totalUnresolvedPairs = 0;
					for (TNode cn: node.getChildren()) {
						BitSet c = ((STITreeCluster) ((STINode)cn).getData()).getBitSet();
						children.add(c);
						long cc = c.cardinality();
						totalPairs += cc*(cc-1);
						totalUnresolvedPairs += cc * (treeall - cc); 
					}
					if (others.cardinality() != 0) {
						children.add(others);
						long cc = others.cardinality();
						totalPairs += cc*(cc-1);
						totalUnresolvedPairs += cc * (treeall - cc);
					}
					totalPairs /= 2;
					totalUnresolvedPairs /= 2;


					for (int j = 0; j < children.size(); j++ ) {
						BitSet left = children.get(j);
						long lc = left.cardinality();
						long lcu = lc * (treeall - lc);
						long lcp = lc*(lc-1)/2;
						for (int i = j+1; i < children.size(); i++ ) {
							BitSet right = children.get(i);
							long rc = right.cardinality();
							long rcu = rc * (treeall - lc - rc);
							long rcp = rc*(rc-1)/2;
							double sim = (totalPairs - lcp - rcp) // the number of fully resolved quartets
									//+ (totalUnresolvedPairs - lcu - rcu) / 3.0 // we count partially resolved quartets
									; 
							updateQuartetDistanceTri( left, right, array, sim);
						}
					}
				}

				BitSet all = treeallCL.getBitSet();
				int c = all.cardinality() - 2;
				for (int l = all.nextSetBit(0); l >= 0; l=all.nextSetBit(l+1)) {
					for (int r = all.nextSetBit(0); r >= 0; r=all.nextSetBit(r+1)) {
						denom[l][r] += c*(c-1)/2;
						denom[r][l] = denom[l][r];
					}
				}
			}
			return new float[][][]{array, denom};
		}
	}

    @Override
    public boolean isDistance() {
        return false;
    }
    
	@Override
    public List<BitSet> inferTreeBitsets() {
    
        return UPGMA();
    }

    @Override
    public Matrix populate(List<STITreeCluster> treeAllClusters, List<Tree> geneTrees, SpeciesMapper spm) {
        this.populateByQuartetDistance(treeAllClusters, geneTrees);
        return spm.convertToSpeciesDistance(this);
    }

    @Override
    public List<BitSet> resolvePolytomy(List<BitSet> bsList, boolean original) {
        return resolveByUPGMA(bsList, original);
    }

    @Override
    Matrix factory(float[][] from) {
        return new SimilarityMatrix(from);
    }

	List<BitSet> resolveByUPGMA(List<BitSet> bsList, boolean original) {

		List<BitSet> internalBSList;
		if (original) {
			internalBSList = new ArrayList<BitSet>(bsList);
		} else {
			internalBSList = new ArrayList<BitSet>();
		}

		int size = bsList .size();
		List<TreeSet<Integer>> indsBySim = new ArrayList<TreeSet<Integer>>(size);
		List<float[]> sims = new ArrayList<float[]>(size);
		List<Integer> range = Utils.getRange(size);
		List<Integer> weights = new ArrayList<Integer>(size);

		for (int i = 0; i < size; i++) {
			if (!original) {
				BitSet internalBS = new BitSet(size);
				internalBS.set(i);
				internalBSList.add(internalBS);
			}

			final float[] is = new float[size];// this.matrix[i].clone();
			BitSet bsI = bsList.get(i);
			weights.add(bsI.cardinality());
			sims.add(is);

			for (int j = 0; j < size; j++) {

				BitSet bsJ = bsList.get(j);
				int c = 0;
				if (i == j) {
					is[j] = 1F;
					continue;
				}
				for (int k = bsI.nextSetBit(0); k >= 0; k = bsI.nextSetBit(k + 1)) {
					for (int l = bsJ.nextSetBit(0); l >= 0; l = bsJ.nextSetBit(l + 1)) {
						is[j] += this.matrix[k][l];
						c++;
					}
				}
				if (c == 0) {
					throw new RuntimeException("Error: "+bsI + " "+bsJ);
				}
				is[j] /= c;
			}

			range.remove(i);
			TreeSet<Integer> sortColumn = this.sortColumn(range, is);
			range.add(i,i);
			indsBySim.add(sortColumn);
		}

		return upgmaLoop(weights, internalBSList, indsBySim, sims, size,false);
	}

	List<BitSet> UPGMA() {

		List<BitSet> bsList = new ArrayList<BitSet>(n);
		List<TreeSet<Integer>> indsBySim = new ArrayList<TreeSet<Integer>>(n);
		List<float[]> sims = new ArrayList<float[]>(n);
		List<Integer> range = Utils.getRange(n);
		List<Integer> weights = Utils.getOnes(n);

		for (int i = 0; i< n; i++) {
			BitSet bs = new BitSet(64);
			bs.set(i);
			bsList.add(bs);
			final float[] is = this.matrix[i].clone();
			sims.add(is);
			range.remove(i);
			TreeSet<Integer> sortColumn = this.sortColumn(range, is);
			range.add(i,i);
			indsBySim.add(sortColumn);
		}

		return upgmaLoop(weights, bsList, indsBySim, sims, n, false);
	}

	private List<BitSet> upgmaLoop(List<Integer> weights, List<BitSet> bsList,
			List<TreeSet<Integer>> indsBySim, List<float[]> sims, int left,boolean randomize) {
		List<BitSet> ret = new ArrayList<BitSet>();
		while ( left > 2) {
			int closestI = -1;
			int closestJ = -1;
			Float bestHit = -1F;
			for (int i = 0; i < indsBySim.size(); i++) {
				if (indsBySim.get(i) == null)
					continue;
				int j = indsBySim.get(i).first();
				if (sims.get(i)[j] > bestHit || (randomize & sims.get(i)[i] == bestHit & GlobalMaps.random.nextBoolean())) {
					bestHit = sims.get(i)[j];
					closestI = i;
					closestJ = j;
				}
			}
			BitSet bs = (BitSet) bsList.get(closestI).clone();
			bs.or(bsList.get(closestJ));
			bsList.set(closestJ,null);
			bsList.set(closestI,bs);

			float[] jDist = sims.get(closestJ);
			float[] iDist = sims.get(closestI).clone();
			for (int k = 0; k < sims.size(); k++) {
				if (k == closestJ || sims.get(k) == null) {
					continue;
				}

				if ( k != closestI) {
					Float newSimToI = (iDist[k] * weights.get(closestI) + jDist[k] * weights.get(closestJ))/( weights.get(closestI)+ weights.get(closestJ));

					indsBySim.get(k).remove(closestI);
					sims.get(k)[closestI] = newSimToI;
					indsBySim.get(k).add(closestI);

					indsBySim.get(closestI).remove(k);
					sims.get(closestI)[k] = newSimToI;
					indsBySim.get(closestI).add(k);
				}

				indsBySim.get(k).remove(closestJ);
				sims.get(k)[closestJ] = -1F;
				//indsBySim.get(k).add(closestJ);
			}

			sims.set(closestJ,null);
			indsBySim.set(closestJ,null);
			weights.set(closestI, weights.get(closestI)+weights.get(closestJ));
			weights.set(closestJ,null);
			ret.add(bs);
			left--;
		}
		return ret;
	}
	
	public void fillZero2D(Float[][] array) {
		for(int i = 0; i < array.length; i++) {
			for(int j = 0; j < array[0].length; j++) {
				array[i][j] = 0F;
			}
		}
	}
}