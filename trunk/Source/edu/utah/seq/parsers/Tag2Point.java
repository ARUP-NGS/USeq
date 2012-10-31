package edu.utah.seq.parsers;

import java.io.*;
import java.util.regex.*;
import edu.utah.seq.data.*;
import util.gen.*;

import java.util.*;

/**Splits a tag file (chr start stop ... strand, ie xxx.bed) file by chromosome and strand.  
 * For each region a single hit is assigned to the 
 * center position. Files are saved using the bar format.
 * Final positions are in interbase coordinates.
 * @author david.nix@hci.utah.edu 
 **/
public class Tag2Point {
	//fields
	private File[] tagFiles;
	private File workingDirectory;
	private File workingTagFile;
	private File[] workingSplitTagFiles;
	private String versionedGenome;
	private int subtractFromBeginning = 0;
	private int addToEnd = 0;
	private int shift3Prime = 0;
	private int strandColumnIndex = 5;
	private int readLength = 0;
	private boolean throwReadLengthWarning;
	private boolean sumHits = false;
	private boolean appendChr = false;
	private boolean verbose = true;
	private int numberParsedRegions = 0;

	//constructors
	/**Stand alone*/
	public Tag2Point(String[] args){
		processArgs(args);
		if (subtractFromBeginning !=0) System.out.println("Subtracting "+subtractFromBeginning+" from the beginning of each region.");
		if (subtractFromBeginning !=0) System.out.println("Adding "+addToEnd+" to the stop of each region.");
		if (shift3Prime !=0) System.out.println("Adding "+shift3Prime+" to 3' stranded stop of each region.");
		System.out.println("Converting...");

		//for each tag file, parse, sort, split
		for (int i=0; i< tagFiles.length; i++){
			//reset warning
			throwReadLengthWarning = true;

			//set working objects and parse tag file text
			workingTagFile = tagFiles[i];
			System.out.println("\t"+workingTagFile);
			String truncName = Misc.removeExtension(workingTagFile.getName());

			//set working directories
			workingDirectory = new File (workingTagFile.getParentFile(), truncName+"_Point");
			if (workingDirectory.exists()) Misc.printExit("\nError: cannot make a save directory, it already exits -> "+workingDirectory+"\n"); 
			else workingDirectory.mkdir();

			//split tag file to chromosome specific temp files in barDirectory
			if (splitTagToTemp() == false) Misc.printExit("\nError: failed to make split temp files for "+workingTagFile+"\n");

			//for each temp file convert to PointData
			if (convertTempFiles() == false) Misc.printExit("\nError: failed to convert split temp files to point data files for "+workingTagFile+"\n");
		}

		System.out.println("\nDone!\n");
	}

	/**For working with ChIPSeq and RNASeq*/
	public Tag2Point(File saveDirectory, File[] dataFiles, String versionedGenome){
		tagFiles = dataFiles;
		this.versionedGenome = versionedGenome;
		this.workingDirectory = saveDirectory;
		workingDirectory.mkdirs();
		verbose = false;
		//split tag file to chromosome specific temp files in barDirectory
		if (splitBedFilesToTemp() == false) Misc.printExit("\nError: failed to make split bed temp files.");

		//for each temp file convert to PointData
		if (convertTempFiles() == false) Misc.printExit("\nError: failed to convert split temp files to point data.");
	}



	public Tag2Point(){

	}

	public boolean convertTempFiles(){
		try {
			int totalTags = 0;
			if (verbose) System.out.print("\t\t");
			//for each split file of centered positions
			for (int j= 0; j< workingSplitTagFiles.length; j++){
				//get chromosome and strand
				String chromStrand = workingSplitTagFiles[j].getName();
				String[] tokens = chromStrand.split("_");
				String chrom = tokens[0];
				String strand = tokens[1];
				//load positions
				int[] positions = Num.loadInts(workingSplitTagFiles[j]);
				if (verbose) System.out.print(chromStrand+" "+positions.length+", ");
				totalTags += positions.length;
				numberParsedRegions += positions.length;
				//sort
				Arrays.sort(positions);
				//make and load PointData with hit counts, nulls positions int[]
				PointData pd = loadPositionValues(positions);
				//make Info object and place in PointData
				HashMap <String,String> notes = new HashMap <String,String> ();
				notes.put(BarParser.SOURCE_TAG, workingTagFile.getCanonicalPath());
				if (shift3Prime !=0) notes.put(BarParser.BP_3_PRIME_SHIFT, shift3Prime+"");
				notes.put(BarParser.GRAPH_TYPE_TAG, BarParser.GRAPH_TYPE_BAR);
				notes.put(BarParser.STRAND_TAG, strand);
				notes.put(BarParser.READ_LENGTH_TAG, readLength+"");
				notes.put(BarParser.DESCRIPTION_TAG, "Generated by running the Tag2Point parser, the position is assigned to the shifted middle of the read, interbase coordinates");
				Info info = new Info(workingSplitTagFiles[j].getCanonicalPath(), versionedGenome, chrom, strand, readLength, notes);
				pd.setInfo(info);
				//write zip compressed PointData object as a bar file
				pd.writePointData(workingDirectory);
				//clean up
				pd.nullPositionScoreArrays();
				pd = null;
				workingSplitTagFiles[j].deleteOnExit();
				System.gc();
			}
			if (verbose) System.out.println("\n\t\tTotal tags\t"+totalTags);
		} catch (Exception e){
			e.printStackTrace();
			return false;
		}
		return true;
	}

	public PointData loadPositionValues(int[] sortedPositions){
		int[] pos;
		float[] scores;
		if (sumHits){
			//count the number of each position, duplicates expected
			int hits = 1;
			int position = sortedPositions[0];
			ArrayList <int[]> hitCount = new ArrayList <int[]> (sortedPositions.length/4);
			for (int i=1; i< sortedPositions.length; i++){
				//new position?
				if (sortedPositions[i] != position){
					//save in ArrayList
					hitCount.add(new int[]{position, hits});
					//reset
					hits = 1;
					position = sortedPositions[i]; 
				}
				//nope old position, increment counts
				else hits++;
			}
			//add last
			hitCount.add(new int[]{position, hits});
			//null sortedPositions 
			sortedPositions = null;
			//make int[] and float[] for PointData
			int numPos = hitCount.size();
			pos = new int[numPos];
			scores = new float[numPos];
			for (int i=0; i< numPos; i++){
				int[] np = hitCount.get(i);
				pos[i] = np[0];
				scores[i] = np[1];
			}
			hitCount = null;
		}
		else {
			pos = sortedPositions;
			scores = new float[pos.length];
			Arrays.fill(scores, 1);
		}
		//load PointData
		PointData pd = new PointData();
		pd.setPositions(pos);
		pd.setScores(scores);
		return pd;
	}

	/**Splits a tag file by chromosome and strand to seperate files.*/
	public boolean splitBedFilesToTemp(){
		try{
			//make hash to hold print writers
			HashMap <String,PrintWriter> chromPW = new HashMap <String,PrintWriter> ();
			//make ArrayList to hold split files
			ArrayList<File> filesAL = new ArrayList<File>();
			//read in tag file
			String chr;
			Pattern pat = Pattern.compile("\\s+");

			//for each tag file
			for (int i=0; i< tagFiles.length; i++){

				//set working objects and parse tag file text
				workingTagFile = tagFiles[i];

				//make reader
				BufferedReader in = IO.fetchBufferedReader(workingTagFile);

				//read in first lines, set print writer, current chromosome, and print line
				String line = in.readLine();
				while (line.startsWith("track") || line.startsWith("#") || line.trim().length()==0) line = in.readLine();
				String[] tokens = pat.split(line);
				if (tokens.length < 4) Misc.printExit("\nProblem splitting line, missing columns? See -> '"+line+"', aborting.\n");
				String currentChr = tokens[0]+"_"+tokens[strandColumnIndex];

				//check strand
				if (tokens[strandColumnIndex].equals("+") == false && tokens[strandColumnIndex].equals("-") == false) Misc.printExit(tokens[strandColumnIndex]+"\nProblem parsing strand information, are you using '+' or '-' symbols? See ->"+line);
				File chrFile = new File (workingDirectory, currentChr);
				filesAL.add(chrFile);
				PrintWriter out = new PrintWriter (new FileWriter (chrFile));
				out.println(calculateMiddleBedFile(tokens));
				chromPW.put(currentChr, out);
				//calculate and set read length using other middle method
				if (readLength == 0) calculateMiddle(tokens);

				//read in remainder
				while ((line = in.readLine()) !=null){
					//extract chromosome strand
					tokens = pat.split(line);
					if (tokens.length < 4) Misc.printExit("\nProblem splitting line, missing columns? See -> '"+line+"', aborting.\n");
					chr = tokens[0]+"_"+tokens[strandColumnIndex];
					//different chromosome?
					if (chr.equals(currentChr) == false){
						currentChr = chr;
						//does it exist?
						if (chromPW.containsKey(currentChr)) out = chromPW.get(currentChr);
						else {
							chrFile = new File (workingDirectory, currentChr);
							filesAL.add(chrFile);
							out = new PrintWriter (new FileWriter (chrFile));
							chromPW.put(currentChr, out);
						}
					}
					//write out centered position
					int middle = calculateMiddleBedFile(tokens);
					out.println(middle);
				}
				in.close();
			}

			//close all print writers
			Iterator it = chromPW.keySet().iterator();
			while (it.hasNext()){
				PrintWriter out = chromPW.get(it.next());
				out.close();
			}

			//make and set files
			workingSplitTagFiles = new File[filesAL.size()];
			filesAL.toArray(workingSplitTagFiles);
			Arrays.sort(workingSplitTagFiles);
			return true;
		} catch (Exception e){
			e.printStackTrace();
			return false;
		}
	}


	/**Splits a tag file by chromosome and strand to seperate files.*/
	public boolean splitTagToTemp(){
		try{
			//make hash to hold print writers
			HashMap <String,PrintWriter> chromPW = new HashMap <String,PrintWriter> ();
			//make ArrayList to hold split files
			ArrayList<File> filesAL = new ArrayList();
			//read in tag file
			String chr;
			Pattern pat = Pattern.compile("\\s+");
			BufferedReader in = IO.fetchBufferedReader(workingTagFile);
			//read in first line, set print writer, current chromosome, and print line
			String line = in.readLine();
			while (line.startsWith("track") || line.startsWith("#") || line.trim().length()==0) line = in.readLine();
			String[] tokens = pat.split(line);
			if (tokens.length < 4) Misc.printExit("\nProblem splitting line, missing columns? See -> '"+line+"', aborting.\n");
			String currentChr = tokens[0]+"_"+tokens[strandColumnIndex];
			if (appendChr) currentChr = "chr"+currentChr;
			//check strand
			if (tokens[strandColumnIndex].equals("+") == false && tokens[strandColumnIndex].equals("-") == false) Misc.printExit(tokens[strandColumnIndex]+"\nProblem parsing strand information, are you using '+' or '-' symbols? See ->"+line);
			File chrFile = new File (workingDirectory, currentChr);
			filesAL.add(chrFile);
			PrintWriter out = new PrintWriter (new FileWriter (chrFile));
			out.println(calculateMiddle(tokens));
			chromPW.put(currentChr, out);
			//read in remainder
			while ((line = in.readLine()) !=null){
				//extract chromosome strand
				tokens = pat.split(line);
				if (tokens.length < 4) Misc.printExit("\nProblem splitting line, missing columns? See -> '"+line+"', aborting.\n");
				chr = tokens[0]+"_"+tokens[strandColumnIndex];
				if (appendChr) chr = "chr"+chr;
				//different chromosome?
				if (chr.equals(currentChr) == false){
					currentChr = chr;
					//does it exist?
					if (chromPW.containsKey(currentChr)) out = chromPW.get(currentChr);
					else {
						chrFile = new File (workingDirectory, currentChr);
						filesAL.add(chrFile);
						out = new PrintWriter (new FileWriter (chrFile));
						chromPW.put(currentChr, out);
					}
				}
				//write out centered position
				int middle = calculateMiddle(tokens);
				//shift for strandedness?
				if (shift3Prime !=0){
					if (tokens[strandColumnIndex].equals("+")) middle += shift3Prime;
					else if (tokens[strandColumnIndex].equals("-")) {
						middle -= shift3Prime;
						if (middle <= 0) middle = 0;
					}
				}
				out.println(middle);
			}
			//close all print writers
			Iterator it = chromPW.keySet().iterator();
			while (it.hasNext()){
				out = chromPW.get(it.next());
				out.close();
			}
			in.close();
			//make and set files
			workingSplitTagFiles = new File[filesAL.size()];
			filesAL.toArray(workingSplitTagFiles);
			Arrays.sort(workingSplitTagFiles);
			return true;
		} catch (Exception e){
			System.err.println("\nError parsing Tag file or writing split binary chromosome files.\nToo many open files? Too many chromosomes? " +
			"If so then login as root and set the default higher using the ulimit command (e.g. ulimit -n 10000)\n");
			e.printStackTrace();

			return false;
		}
	}

	/**Using interbase coordinates so length = stop - start.*/
	public int calculateMiddle(String[] tokens)throws Exception{
		int start = Integer.parseInt(tokens[1]) - subtractFromBeginning;
		int end = Integer.parseInt(tokens[2]) + addToEnd;
		int length = end - start;
		if (readLength == 0 ) readLength = length;
		else if (throwReadLengthWarning && readLength != length){
			System.out.println ("\n\tWARNING: length of reads differ! Expecting: "+readLength+" found: "+length +" from "+Misc.stringArrayToString(tokens, "\t")+"\n");
			throwReadLengthWarning = false;
		}
		double halfLength = ((double)length)/2.0;
		int middle = (int)Math.round(halfLength) + start;
		return middle;

	}

	/**Using interbase coordinates so length = stop - start.*/
	public int calculateMiddleBedFile(String[] tokens)throws Exception{
		int start = Integer.parseInt(tokens[1]);
		int end = Integer.parseInt(tokens[2]);
		double length = end - start;
		double halfLength = length/2.0;
		int middle = (int)Math.round(halfLength) + start;
		return middle;
	}


	public static void main(String[] args) {
		if (args.length ==0){
			printDocs();
			System.exit(0);
		}
		new Tag2Point(args);
	}		

	/**This method will process each argument and assign new varibles*/
	public void processArgs(String[] args){
		Pattern pat = Pattern.compile("-[a-z]");
		System.out.println("\n"+IO.fetchUSeqVersion()+" Arguments: "+Misc.stringArrayToString(args, " ")+"\n");
		for (int i = 0; i<args.length; i++){
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()){
				char test = args[i].charAt(1);
				try{
					switch (test){
					case 'f': tagFiles = IO.extractFiles(new File (args[i+1])); i++; break;
					case 'v': versionedGenome = args[i+1]; i++; break;
					case 'b': subtractFromBeginning = 1; break;
					case 'e': addToEnd = 1; break;
					case 'c': appendChr = true; break;
					case 's': shift3Prime = Integer.parseInt(args[i+1]); i++; break;
					case 'i': strandColumnIndex = Integer.parseInt(args[i+1]); i++; break;
					case 'h': printDocs(); System.exit(0);
					default: System.out.println("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Misc.printExit("\nSorry, something doesn't look right with this parameter: -"+test+"\n");
				}
			}
		}
		if (tagFiles == null || tagFiles[0].canRead() == false) Misc.printExit("\nError: cannot find your tag file(s)!\n");
		if (versionedGenome == null) Misc.printExit("\nPlease enter a genome version recognized by UCSC, see http://genome.ucsc.edu/FAQ/FAQreleases.\n");
	}	

	public static void printDocs(){
		System.out.println("\n" +
				"**************************************************************************************\n" +
				"**                                Tag2Point: May 2010                               **\n" +
				"**************************************************************************************\n" +
				"Splits and converts tab delimited text (chr start stop ... strand (+ or -)) text\n" +
				"files into center position binary xxx.bar files. Use the appropriate options\n" +
				"to convert your coordinates into interbase coordiantes (zero based, stop excluded).\n\n" +

				"-v Versioned Genome (e.g. H_sapiens_Mar_2006), see UCSC Browser,\n"+
				"      http://genome.ucsc.edu/FAQ/FAQreleases.\n" +
				"-i Strand column index, defaults to 5. 1st column is zero.\n"+
				"-b Subtract one from the beginning of each region.\n"+
				"-e Add one to the stop of each region.\n"+
				"-s Shift centered position x bps 3', defaults to 0.\n"+
				"-f The full path directory/file text of your text file(s) (.gz/.zip OK) .\n" +
				"-c Append 'chr' onto the chromosome column (your data lacks the prefix).\n"+

				"\nExample: java -Xmx1500M -jar pathTo/T2/Apps/Tag2Point -f /Solexa/BedFiles/\n" +
				"     -v H_sapiens_Mar_2006 -b \n\n" +

		"**************************************************************************************\n");

	}

	public int getNumberParsedRegions() {
		return numberParsedRegions;
	}	

}
