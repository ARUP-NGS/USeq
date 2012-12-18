
package edu.utah.seq.parsers;

import java.io.*;
import java.util.regex.*;
import util.gen.*;
import java.util.*;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordIterator;
import net.sf.samtools.SAMFileHeader.SortOrder;
import edu.utah.seq.data.sam.*;

/**
 * @author david.nix@hci.utah.edu 
 **/
public class MergePairedSamAlignments{

	//user defined fields
	private File[] dataFiles;
	private File saveFile;
	private File outputFile;
	private float maximumAlignmentScore = 120;
	private float minimumMappingQualityScore = 13;
	private boolean secondPairReverseStrand = false;
	private boolean removeControlAlignments = false;
	private int minimumDiffQualScore = 3;
	private double minimumFractionInFrameMismatch = 0.01;
	private int maximumProperPairDistanceForMerging = 5000;

	//counters for initial filtering
	private int numberAlignments = 0;
	private int numberUnmapped = 0;
	private int numberFailingVendorQC = 0;
	private int numberPassingAlignments = 0;
	private int numberFailingAlignmentScore = 0;
	private int numberFailingMappingQualityScore = 0;
	private int numberAdapter = 0;
	private int numberPhiX = 0;

	//for trimming/ merging paired data
	ArrayList<SamAlignment> firstPairs = new ArrayList<SamAlignment>();
	ArrayList<SamAlignment> secondPairs = new ArrayList<SamAlignment>();

	private int numberPrintedAlignments = 0;
	private double numberOverlappingBases = 0;
	private double numberNonOverlappingBases = 0;
	private double numberMergedPairs = 0;
	private double numberFailedMergedPairs = 0;
	private int numberNonPairedAlignments = 0;
	private int numberNonProperPairedAlignments = 0;
	private int numberUnmappedMatePairedAlignments = 0;
	private int numberAlignmentsMissingPair;
	private int numberPairsFailingChrDistStrand;
	private boolean crossCheckMateCoordinates = false;
	private int numberPairsFailingMateCrossCoordinateCheck;
	private int numberRepeatAlignmentsLackingMate;


	//internal fields
	private PrintWriter samOut;
	private Gzipper failedSamOut = null;
	private Gzipper failedMatePairOut = null;
	private LinkedHashSet<String> samHeader = new LinkedHashSet<String>();
	private Pattern CIGAR_SUB = Pattern.compile("(\\d+)([MDIN])");
	private Pattern CIGAR_BAD = Pattern.compile(".*[^\\dMDIN].*");
	private Histogram insertSize = new Histogram(0,2001,2001);
	private String programArguments;

	//constructors
	public MergePairedSamAlignments(String[] args){
		try {
			long startTime = System.currentTimeMillis();
			processArgs(args);

			doWork();

			//finish and calc run time
			double diffTime = ((double)(System.currentTimeMillis() -startTime))/60000;
			System.out.println("\nDone! "+Math.round(diffTime)+" Min\n");

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void doWork() throws IOException{
		//make print writer
		outputFile = new File(saveFile+"_temp");
		samOut = new PrintWriter( new FileWriter (outputFile));

		//make gzipper for failed examples
		String name = Misc.removeExtension(saveFile.getName());
		File failedMateExamples = new File(saveFile.getParentFile(), name+"_FailedMateExamples.sam.gz");
		failedMatePairOut = new Gzipper(failedMateExamples);
		failedMatePairOut.println("#All of the following example pairs, max 10 each, were saved to the standard output file " +
		"but failed an attempted merge for the noted reasons.  Use these as a diagnostic to evaluate your paticular sam processing pipeline.");

		File failedReadOutputFile = new File(saveFile.getParentFile(), name+"_UnMappedPoorScore.sam.gz");
		failedSamOut = new Gzipper(failedReadOutputFile);

		//for each file, parse and save to disk	
		System.out.println("\nParsing, filtering, and merging SAM/BAM files...");
		for (int i=0; i< dataFiles.length; i++){
			System.out.print("\t"+dataFiles[i].getName());
			if (dataFiles[i].getName().endsWith(".bam")) parseBamFile(dataFiles[i]);
			else parseTextFile(dataFiles[i]); 
			System.out.println();
		}

		//close the writers
		samOut.close();
		failedMatePairOut.close();
		failedSamOut.close();

		//add header and output results, this deletes the outputFile too
		if (saveFile.getName().endsWith(".sam")){
			System.out.println("\nAdding SAM header and gzip compressing xxx.sam file...");
			addHeaderAndCompress();
		}
		else {
			System.out.println("\nAdding SAM header, sorting, and writing bam output with Picard's SortSam...");
			addHeaderAndSort();
		}

		//stats
		double fractionPassing = ((double)numberPassingAlignments)/((double)numberAlignments);
		System.out.println("\nStats (some flags aren't set so be suspicious of zero read catagories):\n");
		System.out.println("\t"+numberAlignments+"\tTotal # alignments from sam/bam file");
		System.out.println("\t"+numberPassingAlignments+"\tAlignments passing individual read filters ("+Num.formatPercentOneFraction(fractionPassing)+")");
		System.out.println("\t\t"+numberUnmapped+"\t# Unmapped reads");
		System.out.println("\t\t"+numberFailingVendorQC+"\t# Alignments failing vendor/ platform QC");
		System.out.println("\t\t"+numberFailingAlignmentScore+"\t# Alignments failing alignment score ("+(int)maximumAlignmentScore+")");
		System.out.println("\t\t"+numberFailingMappingQualityScore+"\t# Alignments failing mapping quality score ("+(int)minimumMappingQualityScore+")");
		System.out.println("\t\t"+numberAdapter+"\t# Adapter alignments");
		System.out.println("\t\t"+numberPhiX+"\t# PhiX alignments");
		System.out.println();
		System.out.println("\nPaired alignment stats:\n");
		System.out.println("\t\t"+numberNonPairedAlignments+"\t# Non paired alignments");
		System.out.println("\t\t"+numberNonProperPairedAlignments+"\t# Non proper paired alignments");
		System.out.println("\t\t"+numberUnmappedMatePairedAlignments+"\t# Non mapped mate paired alignments");
		System.out.println("\t\t"+numberAlignmentsMissingPair+"\t# Alignments missing mate paired alignment");
		System.out.println("\t\t"+numberPairsFailingChrDistStrand+"\t# Paired alignments failing chromosome, distance, or strand check");
		System.out.println("\t\t"+numberPairsFailingMateCrossCoordinateCheck+"\t# Paired alignments failing mate pair cross coordinate check");
		System.out.println("\t\t"+numberRepeatAlignmentsLackingMate+"\t# Repeat alignments lacking a mate");
		System.out.println("\t\t"+(int)numberFailedMergedPairs+"\t# Proper paired alignments that could not be unambiguously merged");
		System.out.println("\t\t"+(int)numberMergedPairs+"\t# Proper paired alignments that were merged");
	
		double fractionFailed = numberFailedMergedPairs/ (numberFailedMergedPairs+ numberMergedPairs);
		System.out.println("\t\t\t"+Num.formatNumber(fractionFailed, 4)+"\tFraction proper paired alignments that could not be merged");
		double totalBases = numberNonOverlappingBases + numberOverlappingBases;
		double fractionOverlap = numberOverlappingBases/totalBases;
		String fractionString = Num.formatNumber(fractionOverlap, 4);
		System.out.println("\t\t\t"+fractionString+"\tFraction overlapping bases in proper paired alignments");		
		//histogram
		System.out.println("\nMapped genomic insert length distribution for merged paired alignments:");
		insertSize.setSkipZeroBins(true);
		insertSize.setTrimLabelsToSingleInteger(true);
		insertSize.printScaledHistogram();
		
		System.out.println("\n\t"+numberPrintedAlignments+"\t# Alignments written to SAM/BAM file.");


	}

	public boolean parseTextFile(File samFile){
		BufferedReader in = null;
		int numBadLines = 0;
		try {
			in = IO.fetchBufferedReader(samFile);
			String line;
			String priorReadName = "";
			boolean priorSet = false;
			ArrayList<SamAlignment> alignmentsToSave = new ArrayList<SamAlignment>();
			int dotCounter = 0;
			while ((line=in.readLine())!= null) {
				if (++dotCounter > 1000000){
					System.out.print(".");
					dotCounter = 0;
				}
				line = line.trim();

				//skip blank lines
				if (line.length() == 0) continue;

				//header line?
				if (line.startsWith("@")){
					samHeader.add(line);
					continue;
				}

				SamAlignment sa;
				try {
					sa = new SamAlignment(line, false);
				} catch (MalformedSamAlignmentException e) {
					System.out.println("\nSkipping malformed sam alignment -> "+e.getMessage());
					if (numBadLines++ > 100) Misc.printErrAndExit("\nAboring: too many malformed SAM alignments.\n");
					continue;
				}
				numberAlignments++;

				if (checkSamAlignment(sa, line) == false) continue;

				String readName = sa.getName();

				//prior set
				if (priorSet == false){
					priorSet = true;
					priorReadName = readName;
					alignmentsToSave.add(sa);
				}
				//is it an old read name?
				else if (readName.equals(priorReadName)) alignmentsToSave.add(sa);
				//nope new read so process read alignment block
				else {
					filterPrintAlignments(alignmentsToSave);
					//set prior 
					priorReadName = readName;
					//clear 
					alignmentsToSave.clear();
					//add new
					alignmentsToSave.add(sa);
				}
			}
			//process last read alignment block
			filterPrintAlignments(alignmentsToSave);

		} catch (Exception e) {
			e.printStackTrace();
			return false;
		} finally {
			try {
				if (in != null) in.close();
			} catch (IOException e) {}
		}
		return true;
	}



	public boolean parseBamFile(File bamFile){
		SAMFileReader samReader = null;
		int numBadLines = 0;
		try {
			String line;
			String priorReadName = "";
			boolean priorSet = false;
			ArrayList<SamAlignment> alignmentsToSave = new ArrayList<SamAlignment>();
			int dotCounter = 0;

			samReader = new SAMFileReader(bamFile);
			
			//check sort order
			if (samReader.getFileHeader().getSortOrder().compareTo(SortOrder.coordinate) == 0){
				Misc.printErrAndExit("\nError, your bam file appears sorted by coordinate. Sort by query name and restart.\n");
			}

			//load header 
			String[] header = samReader.getFileHeader().getTextHeader().split("\\n");
			for (String h: header) samHeader.add(h);
			
			
			SAMRecordIterator it = samReader.iterator();

			while (it.hasNext()) {
				SAMRecord sam = it.next();
				line = sam.getSAMString();

				if (++dotCounter > 1000000){
					System.out.print(".");
					dotCounter = 0;
				}

				SamAlignment sa;
				try {
					sa = new SamAlignment(line, false);
				} catch (MalformedSamAlignmentException e) {
					System.out.println("\nSkipping malformed sam alignment -> "+e.getMessage());
					if (numBadLines++ > 100) Misc.printErrAndExit("\nAboring: too many malformed SAM alignments.\n");
					continue;
				}
				numberAlignments++;

				if (checkSamAlignment(sa, line) == false) continue;

				String readName = sa.getName();

				//prior set
				if (priorSet == false){
					priorSet = true;
					priorReadName = readName;
					alignmentsToSave.add(sa);
				}
				//is it an old read name?
				else if (readName.equals(priorReadName)) alignmentsToSave.add(sa);
				//nope new read so process read alignment block
				else {
					filterPrintAlignments(alignmentsToSave);
					//set prior 
					priorReadName = readName;
					//clear 
					alignmentsToSave.clear();
					//add new
					alignmentsToSave.add(sa);
				}
			}
			//process last read alignment block
			filterPrintAlignments(alignmentsToSave);

		} catch (Exception e) {
			e.printStackTrace();
			return false;
		} finally {
			if (samReader != null) samReader.close();
		}
		return true;
	}

	/**Checks a bunch of flags and scores to see if alignment should be saved.*/
	public boolean checkSamAlignment(SamAlignment sa, String line) throws IOException{
		//is it aligned?
		if (sa.isUnmapped()) {
			numberUnmapped++;
			failedSamOut.println(line);
			return false;
		}

		//does it pass the vendor qc?
		if (sa.failedQC()) {
			numberFailingVendorQC++;
			failedSamOut.println(line);
			return false;
		}

		//skip phiX and adapter
		boolean firstIsPhiX = sa.getReferenceSequence().startsWith("chrPhiX");
		if (firstIsPhiX){
			numberPhiX++;
			failedSamOut.println(line);
			if (removeControlAlignments) return false;
		}
		boolean firstIsAdapt = sa.getReferenceSequence().startsWith("chrAdapt");
		if (firstIsAdapt){
			numberAdapter++;
			failedSamOut.println(line);
			if (removeControlAlignments) return false;
		}

		//does it pass the scores threshold?
		int alignmentScore = sa.getAlignmentScore();
		if (alignmentScore != Integer.MIN_VALUE){
			if (alignmentScore > maximumAlignmentScore){
				numberFailingAlignmentScore++;
				failedSamOut.println(line);
				return false;
			}
		}

		//check mapping quality for genomic match reads?
		if (minimumMappingQualityScore !=0){
			if (sa.getMappingQuality() < minimumMappingQualityScore){
				numberFailingMappingQualityScore++;
				failedSamOut.println(line);
				return false;
			}
		}

		//modify second read if phiX or adapter; some paired reads have a mate hitting the control chroms
		SamAlignmentFlags saf = null;
		if (removeControlAlignments && sa.isPartOfAPairedAlignment() && sa.isMateUnMapped() == false){
			if ( sa.getMateReferenceSequence().startsWith("chrPhiX") || sa.getMateReferenceSequence().startsWith("chrAdapt")) {
				saf = new SamAlignmentFlags(sa.getFlags());
				saf.setMateUnMapped(true);
				saf.setaProperPairedAlignment(false);
				sa.setUnMappedMate();
				sa.setFlags(saf.getFlags());
			}
		}

		//OK, it passes individual checks, increment counter, now check for pairing.
		numberPassingAlignments++;

		//is it not part of a pair?
		if (sa.isPartOfAPairedAlignment()== false){
			numberNonPairedAlignments++;
			samOut.println(sa);
			numberPrintedAlignments++;
			return false;
		}

		//is it not part of a proper pair?
		if (sa.isAProperPairedAlignment() == false){
			numberNonProperPairedAlignments++;
			samOut.println(sa);
			numberPrintedAlignments++;
			return false;
		}
		//is mate unmapped
		if (sa.isMateUnMapped()){
			numberUnmappedMatePairedAlignments++;
			samOut.println(sa);
			numberPrintedAlignments++;
			return false;
		}

		return true;

	}

	/**Takes a block of alignments all originating from the same fragment.  Attempts to identify pairs and merge them.*/
	public void filterPrintAlignments(ArrayList<SamAlignment> al){
		try {

			//Split by first and second pairs
			firstPairs.clear();
			secondPairs.clear();
			for (SamAlignment sam : al) {
				if (sam.isFirstPair()) firstPairs.add(sam);
				else if (sam.isSecondPair()) secondPairs.add(sam);
				else Misc.printErrAndExit("\nError: seeing non first second pairing! Aborting.\n");
			}
			int numFirstPairs = firstPairs.size();
			int numSecondPairs = secondPairs.size();

			//missing partners due to filtering?
			if (numFirstPairs == 0 || numSecondPairs == 0){
				for (SamAlignment sam : al) {
					numberAlignmentsMissingPair++;
					samOut.println(sam);
					numberPrintedAlignments++;
				}
				return;
			}

			//just one of each, these should be pairs, so do detail diagnostics
			if (numFirstPairs == 1 && numSecondPairs == 1){
				SamAlignment first = firstPairs.get(0);
				SamAlignment second = secondPairs.get(0);
				processPair(first, second);
				return;
			}

			//nope looks like we've got repeat matches. Is it OK to check mate coordinates.
			if (crossCheckMateCoordinates == false){
				Misc.printErrAndExit("\n\nError, aborting, found repeat match pairs yet you've indicated you don't " +
						"want to cross check mate coordinates.  This is the only way to correctly pair repeat " +
						"matches. Restart after resolving -> "+firstPairs.get(0).getName()+"\n");
			}
			//walk through each first looking at seconds to attempt to merge
			SamAlignment[] firsts = fetchSamAlignmentArray(firstPairs);
			SamAlignment[] seconds = fetchSamAlignmentArray(secondPairs);
			//for each first
			for (int i=0; i< firsts.length; i++){
				//for each second
				for (int j=0; j< seconds.length; j++){
					//null? already processed?
					if (seconds[j] == null) continue;
					//check mate info
					if (testMateChrPosition(firsts[i], seconds[j])){
						//OK looks like they are a pair
						//now test if it is OK to attempt a merge
						if (testChrDistStrnd(firsts[i], seconds[j])){
							//attempt a merge
							mergeAndScorePair(firsts[i], seconds[j]);
						}
						//failed distance or strand so not a proper pair
						else {
							samOut.println(firsts[i]);
							samOut.println(seconds[j]);
							numberPrintedAlignments+=2;
							if (numberPairsFailingChrDistStrand < 10){
								failedMatePairOut.println("#pair failing chr dist or strand check");
								failedMatePairOut.println(firsts[i]);
								failedMatePairOut.println(seconds[j]);
							}
							numberPairsFailingChrDistStrand++;
						}
						//regardless of out come set to null so they aren't processed again
						firsts[i] = null;
						seconds[j] = null;
						break;
					}
					//nope, not a mate, check next second
				}


			}
			//OK now walk through arrays and print any that are not null
			for (SamAlignment s: firsts){
				if (s !=null){
					numberRepeatAlignmentsLackingMate++;
					samOut.println(s);
					numberPrintedAlignments++;
				}
			}
			for (SamAlignment s: seconds){
				if (s !=null){
					numberRepeatAlignmentsLackingMate++;
					samOut.println(s);
					numberPrintedAlignments++;
				}
			}




		} catch (Exception e) {
			e.printStackTrace();
			Misc.printErrAndExit("\nProblem printing alignment block!?\n");
		}
	}

	private SamAlignment[] fetchSamAlignmentArray(ArrayList<SamAlignment> al){
		SamAlignment[] sam = new SamAlignment[al.size()];
		al.toArray(sam);
		return sam;
	}


	private void processPair(SamAlignment first, SamAlignment second) throws IOException {
		//check if the pair is from the same chromosome, acceptable distance, and proper strand
		if (testChrDistStrnd(first, second)){
			//cross validate mate information? this is often messed up especially if from the STP app.
			if (crossCheckMateCoordinates){
				if (testMateChrPosition(first, second)){
					//attempt a merge
					mergeAndScorePair(first, second);
				}
				else {
					samOut.println(first);
					samOut.println(second);
					numberPrintedAlignments+=2;
					if (numberPairsFailingMateCrossCoordinateCheck < 10){
						failedMatePairOut.println("#pair failing mate cross coordinate validation");
						failedMatePairOut.println(first);
						failedMatePairOut.println(second);
					}
					numberPairsFailingMateCrossCoordinateCheck++;
				}
			}
			else {
				//attempt a merge
				mergeAndScorePair(first, second);
			}
		}
		else {
			samOut.println(first);
			samOut.println(second);
			numberPrintedAlignments+=2;
			if (numberPairsFailingChrDistStrand < 10){
				failedMatePairOut.println("#pair failing chr dist or strand check");
				failedMatePairOut.println(first);
				failedMatePairOut.println(second);
			}
			numberPairsFailingChrDistStrand++;
		}

	}

	/**Attempts to merge a proper paired alignment.  Increments counters and sends examples to the gzipper.*/
	private void mergeAndScorePair(SamAlignment first, SamAlignment second) throws IOException{
		//collect string rep since the merge method will modify the SamAlignment while merging so if it fails you can output the unmodified SamAlignment
		String firstSamString = first.toString();
		String secondSamString = second.toString();
		SamAlignment mergedSam = mergePairedAlignments(first, second);
		if (mergedSam!=null) {
			numberMergedPairs++;
			samOut.println(mergedSam);
			numberPrintedAlignments++;
		}
		else {
			samOut.println(firstSamString);
			samOut.println(secondSamString);
			numberPrintedAlignments+=2;
			if (numberFailedMergedPairs < 10){
				failedMatePairOut.println("#pair that failed merging, multiple INDELS?");
				failedMatePairOut.println(firstSamString);
				failedMatePairOut.println(secondSamString);
			}
			numberFailedMergedPairs++;
		}

	}

	/**Checks chrom and position of mate info.  This info is often messed up so it probably is a good idea to watch this outcome.*/
	private boolean testMateChrPosition(SamAlignment first, SamAlignment second) {
		//check mate position
		if (first.getMatePosition() != second.getPosition()) return false;
		if (second.getMatePosition() != first.getPosition()) return false;
		
		//check chromosome
		String mateChrom = first.getMateReferenceSequence();
		
		if (mateChrom.equals("=") == false && mateChrom.equals(first.getReferenceSequence()) == false) return false;
		mateChrom = second.getMateReferenceSequence();
		if (mateChrom.equals("=") == false && mateChrom.equals(second.getReferenceSequence()) == false) return false;
		return true;
	}


	/**Checks chrom, max distance, correct strand.  Required before attempting a merge!  Otherwise you'll be merging improper pairs.*/
	private boolean testChrDistStrnd(SamAlignment first, SamAlignment second) {
		if (first.getReferenceSequence().equals(second.getReferenceSequence())) {
			//within acceptable distance
			int diff = Math.abs(first.getPosition()- second.getPosition());
			if (diff < maximumProperPairDistanceForMerging){
				//correct strand?
				if (secondPairReverseStrand){
					if (first.isReverseStrand() != second.isReverseStrand()) return false;
				}
				else if (first.isReverseStrand() == second.isReverseStrand()) return false;
			}
			else return false;
		}
		else return false;

		return true;
	}

	/**Attempts to merge alignments. Doesn't check if proper pairs!  Returns null if it cannot. This modifies the input SamAlignments so print first before calling*/
	private SamAlignment mergePairedAlignments(SamAlignment first, SamAlignment second) {
		//trim them of soft clipped info
		first.trimMaskingOfReadToFitAlignment();
		second.trimMaskingOfReadToFitAlignment();

		//look for bad CIGARs
		if (CIGAR_BAD.matcher(first.getCigar()).matches()) Misc.printErrAndExit("\nError: unsupported cigar string! See -> "+first.toString()+"\n");
		if (CIGAR_BAD.matcher(second.getCigar()).matches()) Misc.printErrAndExit("\nError: unsupported cigar string! See -> "+second.toString()+"\n");

		//fetch coordinates
		int startBaseFirst = first.getPosition();
		int stopBaseFirst = startBaseFirst + countLengthOfCigar(first.getCigar());
		int startBaseSecond = second.getPosition();
		int stopBaseSecond = startBaseSecond + countLengthOfCigar(second.getCigar());

		//make arrays to hold sequence and qualities
		int start = startBaseFirst;
		if (startBaseSecond < start) start = startBaseSecond;
		int stop = stopBaseFirst;
		if (stopBaseSecond > stop) stop = stopBaseSecond;
		int size = stop-start;

		SamLayout firstLayout = new SamLayout(size);
		SamLayout secondLayout = new SamLayout(size);

		//layout data
		firstLayout.layoutCigar(start, first);
		secondLayout.layoutCigar(start, second);

		/*if (first.getName().equals("DQNZZQ1:505:D101DACXX:6:1208:13804:3296") ){
			System.out.println("PreFirstLayout");
			firstLayout.print();
			System.out.println("PreSecondLayout");
			secondLayout.print();
		}*/

		//merge layouts, modifies original layouts so print first if you want to see em before mods.
		SamLayout mergedSamLayout = SamLayout.mergeLayouts(firstLayout, secondLayout, minimumDiffQualScore, minimumFractionInFrameMismatch);

		if (mergedSamLayout == null) {
			//if (true){
			/*System.out.println("Failed to merge! ");
				System.out.println("\nFirst "+first);
				System.out.println("Second "+second);
				System.out.println("\tFirst "+startBaseFirst+" "+stopBaseFirst);
				System.out.println("\tSecond "+startBaseSecond+" "+stopBaseSecond);	
				//remake em since they might have been modified.
				firstLayout = new SamLayout(size);
				secondLayout = new SamLayout(size);
				firstLayout.layoutCigar(start, first);
				secondLayout.layoutCigar(start, second);
				System.out.println("FirstLayout");
				firstLayout.print();
				System.out.println("SecondLayout");
				secondLayout.print();
				if (mergedSamLayout != null) {
					System.out.println("MergedLayout");
					mergedSamLayout.print();
				}*/

			//add failed merge tag
			first.addMergeTag(false);
			second.addMergeTag(false);

			return null;
		}

		else {
			//calculate overlap
			int[] overNonOver = SamLayout.countOverlappingBases(firstLayout, secondLayout);
			numberOverlappingBases+= overNonOver[0];
			numberNonOverlappingBases+= overNonOver[1];

			//make merged
			SamAlignment mergedSam = makeSamAlignment(first, second, mergedSamLayout, start);

			//set insert length
			insertSize.count(mergedSam.countLengthOfAlignment());

			return mergedSam;
		}

	}

	public SamAlignment makeSamAlignment(SamAlignment first, SamAlignment second, SamLayout merged, int position){
		SamAlignment mergedSam = new SamAlignment();
		//<QNAME>
		mergedSam.setName(first.getName());
		//<FLAG>
		SamAlignmentFlags saf = new SamAlignmentFlags();
		saf.setReverseStrand(first.isReverseStrand());
		mergedSam.setFlags(saf.getFlags());
		//<RNAME>
		mergedSam.setReferenceSequence(first.getReferenceSequence());
		//<POS>
		mergedSam.setPosition(position);
		//<MAPQ>, bigger better
		int mqF = first.getMappingQuality();
		int mqS = second.getMappingQuality();
		if (mqF > mqS) mergedSam.setMappingQuality(mqF);
		else mergedSam.setMappingQuality(mqS);
		//<CIGAR>
		mergedSam.setCigar(merged.fetchCigar());
		//<MRNM> <MPOS> <ISIZE>
		mergedSam.setUnMappedMate();
		//<SEQ> <QUAL>
		merged.setSequenceAndQualities(mergedSam);
		/////tags, setting to read with better as score.
		//alternative score, smaller better
		int asF = first.getAlignmentScore();
		int asS = second.getAlignmentScore();
		if (asF != Integer.MIN_VALUE && asS != Integer.MIN_VALUE){
			if (asF < asS) mergedSam.setTags(first.getTags());
			else mergedSam.setTags(second.getTags());
		}
		else mergedSam.setTags(first.getTags());
		//add merged tag
		mergedSam.addMergeTag(true);
		return mergedSam;
	}


	/**Counts the number bases in the cigar string. Only counts M D I and N.*/
	public int countLengthOfCigar (String cigar){
		int length = 0;
		//for each M D I or N block
		Matcher mat = CIGAR_SUB.matcher(cigar);
		while (mat.find()){
			length += Integer.parseInt(mat.group(1));
		}
		return length;
	}


	public void addHeaderAndCompress() throws IOException{
		Gzipper gz = new Gzipper(saveFile);

		//add header 
		//add this program info
		samHeader.add("@PG\tID:MergePairedSamAlignments\tCL: "+programArguments);
		Iterator<String> it = samHeader.iterator();
		while (it.hasNext()) gz.println(it.next());

		//add file contents
		gz.print(outputFile);

		//close it
		gz.close();

		//delete old files
		saveFile.delete();
		outputFile.delete();
	}

	public void addHeaderAndSort() throws IOException{
		//check header size
		if (samHeader.size() == 0){
			System.err.println("\nError! no sam header was found in your file(s), cannot make a sorted bam.  Saving as an unsorted sam.");
			//replace .bam with .sam
			String name = saveFile.getName();
			name = name.replace(".bam", ".sam");
			saveFile = new File(saveFile.getParentFile(), name);
			addHeaderAndCompress();
			return;
		}
		
		File headerFile = new File (saveFile+"_temp.sam");
		PrintWriter out = new PrintWriter(new FileWriter(headerFile));

		//add this program info
		samHeader.add("@PG\tID:MergePairedSamAlignments\tCL: "+programArguments);
		Iterator<String> it = samHeader.iterator();
		while (it.hasNext()) out.println(it.next());
		System.out.println("\nSamHeader: "+samHeader);

		//add file contents
		BufferedReader in = new BufferedReader (new FileReader(outputFile));
		String line;
		while ((line = in.readLine()) != null) out.println(line);

		//close 
		in.close();
		out.close();

		//sort and convert to BAM
		new PicardSortSam (headerFile, saveFile);

		//delete old files
		headerFile.delete();
		outputFile.delete();
	}


	public static void main(String[] args) {
		if (args.length ==0){
			printDocs();
			System.exit(0);
		}
		new MergePairedSamAlignments(args);
	}		


	/**This method will process each argument and assign new varibles*/
	public void processArgs(String[] args){
		Pattern pat = Pattern.compile("-[a-z]");
		File forExtraction = null;
		String useqVersion = IO.fetchUSeqVersion();
		programArguments = useqVersion+" "+Misc.stringArrayToString(args, " ");
		System.out.println("\n"+useqVersion+" Arguments: "+ Misc.stringArrayToString(args, " ") +"\n");
		for (int i = 0; i<args.length; i++){
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()){
				char test = args[i].charAt(1);
				try{
					switch (test){
					case 'f': forExtraction = new File(args[++i]); break;
					case 's': saveFile = new File(args[++i]); break;
					case 'c': removeControlAlignments = true; break;
					case 'm': crossCheckMateCoordinates = true; break;
					case 'd': maximumProperPairDistanceForMerging = Integer.parseInt(args[++i]); break;
					case 'a': maximumAlignmentScore = Float.parseFloat(args[++i]); break;
					case 'q': minimumMappingQualityScore = Float.parseFloat(args[++i]); break;
					default: Misc.printErrAndExit("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Misc.printErrAndExit("\nSorry, something doesn't look right with this parameter: -"+test+"\n");
				}
			}
		}

		//pull files
		File[][] tot = new File[4][];
		tot[0] = IO.extractFiles(forExtraction,".sam");
		tot[1] = IO.extractFiles(forExtraction,".sam.gz");
		tot[2] = IO.extractFiles(forExtraction,".sam.zip");
		tot[3] = IO.extractFiles(forExtraction,".bam");

		dataFiles = IO.collapseFileArray(tot);
		if (dataFiles == null || dataFiles.length==0) dataFiles = IO.extractFiles(forExtraction);
		if (dataFiles == null || dataFiles.length ==0 || dataFiles[0].canRead() == false) Misc.printExit("\nError: cannot find your xxx.sam/bam(.zip/.gz) file(s)!\n");

		//check save file
		if (saveFile != null){
			try {
				saveFile.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (saveFile.canWrite() == false) Misc.printErrAndExit("\nError: cannot create or modify your indicated save file -> "+saveFile);
			if (saveFile.getName().endsWith(".sam") == false && saveFile.getName().endsWith(".bam") == false)  Misc.printErrAndExit("\nError: your indicated save file must end with xxx.sam or xxx.bam -> "+saveFile);
		}
		else {
			String saveFileString;
			if (dataFiles.length == 1) saveFileString = Misc.removeExtension(dataFiles[0].toString());
			else saveFileString = dataFiles[0].getParent();

			String mq = ((int)minimumMappingQualityScore)+"MQ";
			saveFile = new File (saveFileString+"_MPSA"+mq+(int)maximumAlignmentScore+"AS.bam");
		}

		//print info
		{
			System.out.println(maximumAlignmentScore+ "\tMaximum alignment score.");
			System.out.println(minimumMappingQualityScore+ "\tMinimum mapping quality score.");
			System.out.println(removeControlAlignments +"\tRemove control chrPhiX and chrAdapter alignments.");
			System.out.println(maximumProperPairDistanceForMerging +"\tMaximum bp distance for merging paired alignments.\n");

		}

	}	

	public static void printDocs(){
		System.out.println("\n" +
				"**************************************************************************************\n" +
				"**                          MergePairedSamAlignments: Dec 2012                      **\n" +
				"**************************************************************************************\n" +
				"Merges proper paired alignments that pass a variety of checks and\thresholds. Only\n" +
				"unambiguous pairs will be merged. Usefull for avoiding non-independent variant\n" +
				"observations and other double counting issues when reads overlap. Be certain your\n" +
				"input bam/sam file(s) are sorted by query name, NOT coordinate. \n" +

				"\nOptions:\n"+
				"-f The full path file or directory containing raw xxx.sam(.gz/.zip OK)/.bam file(s)\n" +
				"      paired alignments that are sorted by query name (standard novoalign output).\n" +
				"      Multiple files will be merged.\n" +

				"\nDefault Options:\n"+
				"-s Save file, defaults to that inferred by -f. If an xxx.sam extension is provided,\n" +
				"      the alignments won't be sorted by coordinate or saved as a bam file.\n"+
				"-a Maximum alignment score. Defaults to 120, smaller numbers are more stringent.\n" +
				"      Approx 30pts per mismatch for novoalignments.\n"+
				"-q Minimum mapping quality score, defaults to 13, larger numbers are more stringent.\n" +
				"      Set to 0 if processing splice junction indexed RNASeq data.\n"+
				"-r The second paired alignment's strand is reversed. Defaults to not reversed.\n" +
				"-c Don't remove chrAdapt and chrPhiX alignments.\n"+
				"-d Maximum acceptible base pair distance for merging, defaults to 5000.\n"+
				"-m Cross check read mate coordinates, needed for merging repeat matches. Often these\n"+
				"      are incorrect.  Defaults to not checking.\n"+

				"\nExample: java -Xmx1500M -jar pathToUSeq/Apps/MergePairedSamAlignments -f /Novo/Run7/\n" +
				"     -m -s /Novo/STPParsedBams/run7.bam -d 10000 \n\n" +

		"**************************************************************************************\n");

	}

	public int getNumberPassingAlignments() {
		return numberPassingAlignments;
	}

	public int getMinimumDiffQualScore() {
		return minimumDiffQualScore;
	}

	public double getMinimumFractionInFrameMismatch() {
		return minimumFractionInFrameMismatch;
	}	

}
