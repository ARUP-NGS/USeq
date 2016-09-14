package edu.utah.seq.query;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import edu.utah.seq.useq.data.RegionScoreText;

public class TabixQuery {
	
	//fields
	private String chr;
	private int start; //interbase	
	private int stop; //interbase
	private HashMap<File, ArrayList<String>> sourceResults = new HashMap<File, ArrayList<String>>();
	
	//constructors
	/**For bed file region data, interbase coordinates assumed!
	 * @throws IOException */
	public TabixQuery(String chr, RegionScoreText r) throws IOException {
		start = r.getStart();
		stop = r.getStop();
		this.chr = chr;
		//check coor
		if (stop <= start) throw new IOException("\nERROR: the stop is not > the start for TabixQuery "+getInterbaseCoordinates());
	}

	/*Need to synchronize this since multiple threads could be adding results simultaneously.*/
	public synchronized void addResults(File source, ArrayList<String> results){
		sourceResults.put(source, results);
	}

	public String getTabixCoordinates() {
		return chr+":"+(start+1)+"-"+stop;
	}
	
	public String getInterbaseCoordinates() {
		return chr+":"+start+"-"+stop;
	}
	
	public static String getInterbaseCoordinates(ArrayList<TabixQuery> al){
		StringBuilder sb = new StringBuilder();
		sb.append(al.get(0).getInterbaseCoordinates());
		for (int i=1; i< al.size(); i++){
			sb.append(",");
			sb.append(al.get(i).getInterbaseCoordinates());
		}
		return sb.toString();
	}

	public HashMap<File, ArrayList<String>> getSourceResults() {
		return sourceResults;
	}

	public String getChr() {
		return chr;
	}

	public int getStart() {
		return start;
	}

	public int getStop() {
		return stop;
	}
	
	
	
}