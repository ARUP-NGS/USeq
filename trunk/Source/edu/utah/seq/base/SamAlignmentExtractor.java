package edu.utah.seq.base;

import java.io.*;
import java.util.regex.*;
import java.util.*;

import net.sf.samtools.*;
import util.bio.annotation.Bed;
import util.bio.annotation.ExonIntron;
import util.bio.parsers.*;
import util.gen.*;
import edu.utah.seq.analysis.multi.MultipleConditionRNASeq;
import edu.utah.seq.useq.apps.Text2USeq;
import edu.utah.seq.useq.data.RegionScoreText;


/**
 * @author Nix
 * */
public class SamAlignmentExtractor {

	//user defined fields
	private File[] bamFiles;
	private File bedFile;

	//internal fields
	private SAMFileReader[] samReaders;
	private HashMap<String,RegionScoreText[]> chromRegions;
	private String chromosome;
	private static Pattern CIGAR_SUB = Pattern.compile("(\\d+)([MSDHN])");
	private Gzipper samOut;

	//constructors
	/**Stand alone.*/
	public SamAlignmentExtractor(String[] args){
		long startTime = System.currentTimeMillis();
		
		//set fields
		processArgs(args);
		
		//launch
		run();
		
		//finish and calc run time
		double diffTime = ((double)(System.currentTimeMillis() -startTime))/60000;
		System.out.println("\nDone! "+Math.round(diffTime)+" minutes\n");
	}

	public void makeSamReaders(){
		samReaders = new SAMFileReader[bamFiles.length];
		for (int i=0; i< samReaders.length; i++) {
			samReaders[i] = new SAMFileReader(bamFiles[i]);
			samReaders[i].enableIndexMemoryMapping(false);
		}
	}

	public void closeSamReaders(){
		for (int i=0; i< samReaders.length; i++) samReaders[i].close();
	}


	public void run(){
		try {
			//make readers on each bam file
			makeSamReaders();

			//make output writer
			File intSam = new File (bedFile.getParentFile(), Misc.removeExtension(bedFile.getName())+".sam.gz");
			samOut = new Gzipper(intSam);

			//for each chromosome of gene models
			System.out.println("\nScanning regions by chromosome... ");
			System.out.println("\t#IntersectingAlignments\tRegionInfo");
			Iterator<String> it = chromRegions.keySet().iterator();
			while (it.hasNext()){
				chromosome = it.next();
				System.out.println(chromosome);
				scanRegions();
			}
			System.out.println("\n");

			//close readers
			closeSamReaders();
			samOut.close();

		} catch (Exception e) {
			e.printStackTrace();
		} 


	}

	/**Checks that the alignment actually touches down on at least one base of the region to avoid spanners.*/
	public ArrayList<String> fetchAlignments (RegionScoreText ei, SAMFileReader reader){
		ArrayList<String> al = new ArrayList<String>();
		SAMRecordIterator i = reader.queryOverlapping(chromosome, (ei.getStart()+1), ei.getStop());
		while (i.hasNext()) {
			SAMRecord sam = i.next();
			//fetch blocks of actual alignment
			ArrayList<int[]> blocks = fetchAlignmentBlocks(sam.getCigarString(), sam.getUnclippedStart()-1);
			//check to see if any intersect the exon
			for (int[] b : blocks){
				if (ei.intersects(b[0], b[1])){
					al.add(sam.getSAMString().trim());
					break;
				}
			}
		}

		i.close();
		i = null;
		return al;
	}

	/**Assumes interbase coordinates for start and returned blocks.*/
	public static ArrayList<int[]> fetchAlignmentBlocks(String cigar, int start){
		//for each cigar block
		Matcher mat = CIGAR_SUB.matcher(cigar);
		ArrayList<int[]> blocks = new ArrayList<int[]>();
		while (mat.find()){
			String call = mat.group(2);
			int numberBases = Integer.parseInt(mat.group(1));
			//a match
			if (call.equals("M")) {
				blocks.add(new int[]{start, start+numberBases});
			}
			//just advance for all but insertions which should be skipped via the failure to match
			start += numberBases;
		}
		return blocks;
	}

	/**For each sam file, fetches all sam alignments.*/
	public ArrayList<String> fetchOverlappingAlignments (RegionScoreText ei){
		ArrayList<String> ohMy = new ArrayList<String>();
		for (int i=0; i< samReaders.length; i++) {
			ohMy.addAll(fetchAlignments(ei, samReaders[i]));
		}
		return ohMy;
	}

	private void scanRegions() throws IOException{
		RegionScoreText[] regions = chromRegions.get(chromosome);

		//for each region 
		for (int i=0; i< regions.length; i++){
			
			//fetch the overlapping alignments
			ArrayList<String> alignments = fetchOverlappingAlignments (regions[i]);
			
			//print em
			if (alignments.size() !=0) System.out.println("\t"+alignments.size()+"\t"+regions[i].toString());
			samOut.println(alignments);
		}
	}





	public static void main(String[] args) {
		if (args.length ==0){
			printDocs();
			System.exit(0);
		}
		new SamAlignmentExtractor(args);
	}		

	/**This method will process each argument and assign new variables*/
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
					case 'a': bamFiles = IO.extractFiles(args[++i], ".bam"); break;
					case 'b': bedFile = new File(args[++i]); break;
					case 'h': printDocs(); System.exit(0);
					default: Misc.printErrAndExit("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Misc.printErrAndExit("\nSorry, something doesn't look right with this parameter: -"+test+"\n");
				}
			}
		}

		//look for bam files
		if (bamFiles == null || bamFiles.length == 0) Misc.printErrAndExit("\nError: cannot find any treatment xxx.bam files?\n");

		//look for bai index files
		lookForBaiIndexes(bamFiles, false);

		//look for bed
		if (bedFile == null || bedFile.canRead() == false) Misc.printErrAndExit("\nError: cannot find or read your bed file?\n");
		chromRegions = Bed.parseBedFile(bedFile, true);

	}	

	/**Looks for xxx.bam.bai and xxx.bai for each bamFile, prints error and exits if missing.*/
	public static void lookForBaiIndexes (File[] bamFiles, boolean onlyResetLastModifiedDate){
		for (File f: bamFiles){
			File index = new File (f+".bai");
			if (index.exists() == false){
				int len = f.toString().length() - 3;
				index = new File(f.toString().substring(0, len) + "bai");
				if (onlyResetLastModifiedDate == false && index.exists() == false) Misc.printErrAndExit("\nError: failed to find a xxx.bai index file for -> "+f);
			}
			//reset date?
			if (index.exists() && index.lastModified() < f.lastModified()) index.setLastModified(f.lastModified()+1);
		}
	}

	public static void printDocs(){
		System.out.println("\n" +
				"**************************************************************************************\n" +
				"**                            Sam Alignment Extractor: Aug 2012                     **\n" +
				"**************************************************************************************\n" +

				"Given a bed file containing regions of interest, parses all of the intersecting sam\n" +
				"alignments.\n\n"+

				"Required Options:\n"+

				"-a Alignment directory containing one or more xxx.bam files with their associated\n" +
				"       xxx.bai indexs sorted by coordinate.\n" +
				"-b A bed file (chr, start, stop,...), full path, see,\n" +
				"       http://genome.ucsc.edu/FAQ/FAQformat#format1\n"+

				"\n"+

				"Example: java -Xmx4G -jar pathTo/USeq/Apps/SamAlignmentExtractor -a\n" +
				"      /Data/ExonCaptureAlignmentsX1/ -b /Data/SNPCalls/9484X1Calls.bed.gz \n\n" +

		"**************************************************************************************\n");

	}


}