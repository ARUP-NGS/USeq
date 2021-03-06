package util.apps;
import java.io.*;

import util.bio.annotation.Bed;
import util.bio.annotation.ExonIntron;
import util.bio.parsers.UCSCGeneLine;
import util.bio.parsers.UCSCGeneModelTableReader;
import util.gen.*;
import edu.utah.seq.useq.data.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.utah.seq.useq.data.Region;


/**Takes two bed files. Collapses the first onto each chromosome, for each region in the second, calculates the 
 * fraction intersection and sorts into two files, intersecting and non intersecting.*/
public class FilterIntersectingRegions {

	//fields
	private File toFlatten;
	private HashMap<String,Region[]> regionsForFlattening;
	private File[] toSplit;
	private boolean bedFormat = false;
	private boolean ucscFormat = false;
	private HashMap<String,RegionText[]> regionsForSplitting;
	private double minimumFractionIntersection = Double.MIN_VALUE;
	private PrintWriter outIntersected;
	private PrintWriter outNonIntersected;
	private double numberIntersecting = 0;
	private double numberNonIntersecting = 0;
	private int maxGap = 0;

	public FilterIntersectingRegions(String[] args) {
		//process args
		processArgs(args);

		//load regions
		System.out.println("Loading regions...");
		if (maxGap !=0) System.out.println("\tExpanding by +/- "+maxGap);
		regionsForFlattening = Region.parseStartStops(toFlatten, maxGap, (-1*maxGap), 0);
		
		//for each file to split
		for (File splitMe: toSplit){
			System.out.print("Intersecting "+splitMe.getName());
			
			if (ucscFormat) regionsForSplitting = parseUCSCFile(splitMe);
			else regionsForSplitting = parseFile(splitMe, bedFormat);

			//for each chromosome to split
			Iterator<String> it = regionsForSplitting.keySet().iterator();
			File intersected = new File (splitMe.getParentFile(), Misc.removeExtension(splitMe.getName())+"_Int.txt");
			File nonIntersected = new File (splitMe.getParentFile(), Misc.removeExtension(splitMe.getName())+"_NonInt.txt");
			try {
				outIntersected = new PrintWriter( new FileWriter( intersected));
				outNonIntersected = new PrintWriter( new FileWriter( nonIntersected));
				while (it.hasNext()){
					//get text of chromosome and maxBase
					String chrom = (String)it.next();
					//get SS to split
					RegionText[] ssToSplit = regionsForSplitting.get(chrom);
					//make mask
					boolean[] mask = makeMask(chrom);
					//anything to mask?
					if (mask == null) printNonIntersected(ssToSplit, chrom); 
					else {
						RegionText[][] split = intersect(ssToSplit, mask);
						if (ucscFormat) printSplitGenes(split);
						else {
							printIntersected(split[0], chrom);
							printNonIntersected(split[1], chrom);
						}
					}
					System.out.print(".");
				}
				outIntersected.close();
				outNonIntersected.close();
			} catch (IOException e){
				e.printStackTrace();
			}	

			//stats
			System.out.println("\nStats:");
			System.out.println((int)numberIntersecting+"\tIntersecting");
			System.out.println((int)numberNonIntersecting+"\tNon Intersecting");
			double fract = numberIntersecting/(numberIntersecting+numberNonIntersecting);
			System.out.println(Num.formatNumber(fract, 3) +"\tFraction intersecting");
			System.out.println();
			
			//reset
			numberIntersecting = 0;
			numberNonIntersecting = 0;
		}
		System.out.println("\nDone!\n");
	}
	
	private void printSplitGenes(RegionText[][] split) {
		//need to remove dups
		LinkedHashSet<String> intersect = new LinkedHashSet<String>();
		LinkedHashSet<String> nonIntersect = new LinkedHashSet<String>();
		for (int i=0; i< split[0].length; i++) intersect.add(split[0][i].getText());
		for (int i=0; i< split[1].length; i++) nonIntersect.add(split[1][i].getText());

		//print out all intersecting
		for (String gene: intersect) outIntersected.println(gene);
		//print out non ints that aren't in intersecting
		for (String gene: nonIntersect) {
			if (intersect.contains(gene) == false) {
				outNonIntersected.println(gene);
				numberNonIntersecting++;
			}
		}
		numberIntersecting += intersect.size();	
	}

	/**Splits a gff or bed file by chromosome into a HashMap of chromosome : sorted RegionText[].
	 * The Text contains the complete unmodified annotation line.*/
	public static HashMap<String,RegionText[]> parseFile(File file, boolean bedFormat){
		HashMap<String,ArrayList<RegionText>> chrAls = new HashMap<String,ArrayList<RegionText>>();
		Pattern tab = Pattern.compile("\\t");
		String line = null;
		//gff or gtf
		//chrom, programName, featureName, 1baseStart, endIncluded, score0-1000, strand, frame, text
		//chr22  TeleGene      enhancer     100000        100010         500        +      .    touch1
		//0           1           2            3             4            5         6      7      8
		
		//bed
		//chrom, start, stop, name, score strand
		// 0       1      2     3     4      5
		int minColumns = 3;
		int startIndex = 1;
		int stopIndex = 2;
		boolean subtractOne = false;
		if (bedFormat == false){
			minColumns = 9;
			startIndex = 3;
			stopIndex = 4;
			subtractOne = true;
		}
		
		try{
			BufferedReader in = IO.fetchBufferedReader(file);
			String[] tokens;

			ArrayList<RegionText> al = new ArrayList<RegionText>(); 
			String currentChrom = "";
			while ((line = in.readLine()) !=null) {
				line = line.trim();
				if (line.length() ==0 || line.startsWith("#")) continue;
				tokens = tab.split(line);
				if (tokens.length < minColumns) {
					System.err.println("Error: skipping the following annotation line, it does not contain requisit number of tab delimited columns\n\t-> "+line);
					continue;
				}
				//make chromosome strand text
				String chrom = tokens[0];
				
				//fetch ArrayList
				if (currentChrom != chrom){
					currentChrom = chrom;
					if (chrAls.containsKey(currentChrom)) al = chrAls.get(currentChrom);
					else {
						al = new ArrayList<RegionText>(); 
						chrAls.put(currentChrom, al);
					}
				}
				//add entry
				int start = Integer.parseInt(tokens[startIndex]);
				if (subtractOne) start = start -1;
				int stop = Integer.parseInt(tokens[stopIndex]);

				RegionText n = new RegionText(start, stop, line);
				al.add(n);
			}
		}catch (Exception e){
			e.printStackTrace();
			System.out.println("Malformed annotation line? -> "+line);
		}
		//sort and load hash
		HashMap<String,RegionText[]> chrSpec = new HashMap<String,RegionText[]>();
		Iterator<String> it = chrAls.keySet().iterator();
		ArrayList<RegionText> al = null;
		while (it.hasNext()){
			String cs = it.next();
			al = chrAls.get(cs);
			RegionText[] nsss = new RegionText[al.size()];
			al.toArray(nsss);
			Arrays.sort(nsss);
			chrSpec.put(cs, nsss);
		}
		return chrSpec;
	}
	
	
	/**Splits a ucsc ref flat file by chromosome into a HashMap of chromosome : sorted RegionText[].
	 * The Text contains the complete unmodified annotation line.*/
	public static HashMap<String,RegionText[]> parseUCSCFile(File file){
		HashMap<String,RegionText[]> chrRegions = new HashMap<String,RegionText[]>();
	
		UCSCGeneModelTableReader ucsc = new UCSCGeneModelTableReader(file, 0);
		
		HashMap<String, UCSCGeneLine[]> chromLines = ucsc.getChromSpecificGeneLines();
		
		//for each chromosome
		for (String chr: chromLines.keySet()){
			UCSCGeneLine[] genes = chromLines.get(chr);
			ArrayList<RegionText> al = new ArrayList<RegionText>(); 
			
			//for each gene
			for (UCSCGeneLine gene: genes){
				String geneString = gene.toString();
				ExonIntron[] exons = gene.getExons();
				//for each exon, add expander and make into a RegionText
				for (ExonIntron exon: exons){
					int start = exon.getStart();
					int stop = exon.getEnd();
					RegionText n = new RegionText(start, stop, geneString);
					al.add(n);
				}
			}
			RegionText[] nsss = new RegionText[al.size()];
			al.toArray(nsss);
			Arrays.sort(nsss);
			chrRegions.put(chr, nsss);
		}
		return chrRegions;
	}



	/**Returns intersecting and non intersecting*/
	public RegionText[][] intersect(RegionText[] ss, boolean[] bps){
		ArrayList<RegionText> intAL = new ArrayList<RegionText>();
		ArrayList<RegionText> nonIntAL = new ArrayList<RegionText>();

		//for each region count number to trues/ masked bases
		int maxBase = bps.length;
		for (int i=0; i< ss.length; i++){
			double numTrue = 0;
			int start = ss[i].getStart();
			int stop = ss[i].getStop();			
			//past the mask?
			if (start >= maxBase) nonIntAL.add(ss[i]);
			else {
				if (stop > bps.length) stop = bps.length;
				for (int j=start; j< stop; j++) if (bps[j]) numTrue++;
				//calc fraction
				double fractInt = numTrue/(double)ss[i].getLength();
				if (fractInt >= minimumFractionIntersection) intAL.add(ss[i]);
				else nonIntAL.add(ss[i]);
			}
		}
		RegionText[] intersecting = new RegionText[intAL.size()];
		intAL.toArray(intersecting);
		RegionText[] nonIntersecting = new RegionText[nonIntAL.size()];
		nonIntAL.toArray(nonIntersecting);
		return new RegionText[][]{intersecting, nonIntersecting};
	}

	public void printNonIntersected(RegionText[] ss, String chromosome) throws IOException{
		if (ss == null) return;
		if (ucscFormat){
			LinkedHashSet<String> toPrint = new LinkedHashSet<String>();
			for (int i=0; i< ss.length; i++) toPrint.add(ss[i].getText());
			numberNonIntersecting+= toPrint.size();
			for (String s : toPrint) outNonIntersected.println(s);
		}
		else {
			numberNonIntersecting += ss.length;
			for (int i=0; i< ss.length; i++) outNonIntersected.println(ss[i].getText());
		}
	}

	public void printIntersected(RegionText[] ss, String chromosome) throws IOException{
		if (ss == null) return;
		numberIntersecting += ss.length;
		for (int i=0; i< ss.length; i++) outIntersected.println(ss[i].getText());
	}

	public boolean[] makeMask (String chromosome){
		//any mask?
		if (regionsForFlattening.containsKey(chromosome) == false) return null;
		//find max base
		int maxBase = findMaxBase(chromosome);
		//make boolean array to hold whether it's flagged, initially they are all false
		boolean[] bps = new boolean[maxBase+10000];
		//for each RegionScoreText[] scan and throw booleans to true
		Region[] ss = regionsForFlattening.get(chromosome);
		for (int i=0; i<ss.length; i++){
			int start = ss[i].getStart();
			int end = ss[i].getStop();
			for (int j=start; j< end; j++) bps[j] = true;
		}
		return bps;
	}

	public int findMaxBase (String chromosome){
		Region[] ss = regionsForFlattening.get(chromosome);
		int max = ss[ss.length-1].getStop();
		for (int i=0; i< ss.length; i++){
			if (ss[i].getStop()> max) max = ss[i].getStop();
		}
		return max;
	}

	public static void main(String[] args) {
		if (args.length ==0){
			printDocs();
			System.exit(0);
		}
		new FilterIntersectingRegions(args);
	}		

	/**This method will process each argument and assign new varibles*/
	public void processArgs(String[] args){
		Pattern pat = Pattern.compile("-[a-z]");
		System.out.println("\n"+IO.fetchUSeqVersion()+" Arguments: "+Misc.stringArrayToString(args, " ")+"\n");
		String type = null;
		for (int i = 0; i<args.length; i++){
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()){
				char test = args[i].charAt(1);
				try{
					switch (test){
					case 'm': toFlatten = new File (args[++i]); break;
					case 's': toSplit = IO.extractFiles(new File(args[++i])); break;
					case 't': type = args[++i].toLowerCase(); break;
					case 'i': minimumFractionIntersection = Double.parseDouble(args[++i]); break;
					case 'b': maxGap = Integer.parseInt(args[++i]); break;
					case 'h': printDocs(); System.exit(0);
					default: Misc.printErrAndExit("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Misc.printErrAndExit("\nSorry, something doesn't look right with this parameter: -"+test+"\n");
				}
			}
		}
		if (toFlatten == null || toFlatten.canRead() == false) Misc.printErrAndExit("\nCannot find your mask file! "+toFlatten+"\n");
		if (toSplit == null || toSplit.length ==0) Misc.printErrAndExit("\nCannot find your file(s) to split!\n");
		if (type == null ) Misc.printErrAndExit("\nError: please indicate what type of files these are.\n");
		if (type.equals("bed")) bedFormat = true;
		else if (type.equals("ucsc")) ucscFormat = true;
	}	

	public static void printDocs(){
		System.out.println("\n" +
				"**************************************************************************************\n" +
				"**                        Filter Intersecting Regions: Dec 2018                     **\n" +
				"**************************************************************************************\n" +
				"Flattens the mask regions and uses it to split the split file(s) into intersecting\n" +
				"and non intersecting regions based on the minimum fraction intersection. For UCSC gene\n"+
				"tables, exons are compared and if any in the gene intersect, the whole gene is moved\n"+
				"accordingly.\n"+

				"\nOptions:\n"+
				"-m Full path file text for the masking bed file (tab delim: chr start stop ...).\n"+
				"-s Full path file or directory containing bed, gtf/gff, or ucsc gene table files to \n"+
				"        split.\n"+
				"-t Type of files to split, indicate: bed, gff, or ucsc\n"+
				"-i Minimum fraction of each split region required to score as an intersection with\n"+
				"        the flattened mask, defaults to 1x10-1074\n"+
				"-b Expand start and stop of regions to mask by xxx bps, defaults to 0\n"+

				"\nExample: java -Xmx4000M -jar pathTo/Apps/FilterIntersectingRegions -i 0.5\n" +
				"        -m /ArrayDesigns/repMskedDesign.bed -b /ArrayDesigns/ -t bed\n\n" +

		"************************************************************************************\n");
	}

}
