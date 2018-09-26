package phylonet.coalescent;

import phylonet.tree.model.sti.STITreeCluster;

public class Tripartition extends AbstractPartition {
	
	STITreeCluster cluster1;
	STITreeCluster cluster2;	
	STITreeCluster cluster3;
	private int _hash = 0;
	
	public Tripartition(STITreeCluster c1, STITreeCluster c2) {
		STITreeCluster c3 = new STITreeCluster(c1);
		c3.getBitSet().or(c2.getBitSet());
		c3.getBitSet().flip(0,c1.getBitSet().size());
		initialize(c1, c2, c3); 
	}
	
	public Tripartition(STITreeCluster c1, STITreeCluster c2, STITreeCluster c3) {
		
		initialize(c1, c2, c3);
	}
	
	public Tripartition(STITreeCluster c1, STITreeCluster c2, STITreeCluster c3, boolean checkRepeats) {
		if (checkRepeats) initialize(c1, c2, c3);
		else {
			cluster1 = c1;
			cluster2 = c2;
			cluster3 = c3;
		}
	}

	private void initialize(STITreeCluster c1, STITreeCluster c2,
			STITreeCluster c3) {
		if (c1 == null || c2 == null || c3 == null) {
			throw new RuntimeException("none cluster" +c1+" "+c2+" "+c3);
		}
		c1.updateHash();
		c2.updateHash();
		c3.updateHash();
		long n1 = c1.hash1, n2 = c2.hash1, n3 = c3.hash1;
		if (n1 > n2 & n2 > n3) {
			cluster1 = c1;
			cluster2 = c2;
			cluster3 = c3;
		} else if (n1 > n3 & n3 > n2)  {
			cluster1 = c1;
			cluster2 = c3;	
			cluster3 = c2;
		} else if (n2 > n1 & n1 > n3)  {
			cluster1 = c2;
			cluster2 = c1;	
			cluster3 = c3;
		} else if (n2 > n3 & n3 > n1)  {
			cluster1 = c2;
			cluster2 = c3;	
			cluster3 = c1;
		} else if (n3 > n1 & n1 > n2)  {
			cluster1 = c3;
			cluster2 = c1;	
			cluster3 = c2;
		} else if (n3 > n2 & n2 > n1)  {
			cluster1 = c3;
			cluster2 = c2;	
			cluster3 = c1;
		} else {
			throw new RuntimeException("taxa appear multiple times?\n"+c1+"\n"+c2+"\n"+c3);
		}
	}
	
	public STITreeCluster[] getClusters(){
		return new STITreeCluster[]{cluster1, cluster2, cluster3};
	}
	
	@Override
	public boolean equals(Object obj) {
		if ((obj instanceof Tripartition) == false) return false;
		Tripartition trip = (Tripartition) obj; 
		
		return this == obj ||
				((trip.cluster1.equals(this.cluster1) && trip.cluster2.equals(this.cluster2) && trip.cluster3.equals(this.cluster3)));					
	}
	@Override
	public int hashCode() {
		if (_hash == 0) {
			_hash = cluster1.hashCode() * 1089 + cluster2.hashCode() * 33 + cluster3.hashCode();
		}
		return _hash;
	}
	@Override
	public String toString() {		
		return cluster1.toString()+"|"+cluster2.toString()+"|"+cluster3.toString();
	}


}
