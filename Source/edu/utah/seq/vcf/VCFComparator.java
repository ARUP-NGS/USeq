package edu.utah.seq.vcf;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import edu.utah.seq.its.Interval;
import edu.utah.seq.its.IntervalTree;
import edu.utah.seq.useq.data.RegionScoreText;
import util.bio.annotation.Bed;
import util.bio.annotation.ExportIntergenicRegions;
import util.gen.*;

/**Compares variant lists calculating pseudo ROC data 
 * @author Nix
 * */
public class VCFComparator {

	//user fields
	private File vcfKey;
	private File vcfBedKey;
	private File bedKey;
	private File[] testVcfFiles;
	private File vcfTest;
	private File bedTest;
	private File saveDirectory = null;
	private boolean requireGenotypeMatch = false;
	private boolean removeSNPs = false;
	private boolean removeNonSNPs = false;
	private boolean removeNonPass = false;
	private boolean useVQSLOD = false;
	private boolean requireAltMatch = true;
	private int indelBpPad = 0;


	private HashMap<String,RegionScoreText[]> keyBedCalls = null;
	private HashMap<String,IntervalTree<RegionScoreText>> keyIntervalTrees = null;
	private HashMap<String,RegionScoreText[]> keyRegions = null;
	private HashMap<String,RegionScoreText[]> testRegions = null;
	private HashMap<String,RegionScoreText[]> commonRegions = null;
	private VCFParser keyParser = null;
	private VCFParser testParser;
	private VCFMatch[] testMatchingVCF;
	private VCFRecord[] testNonMatchingVCF;
	private VCFRecord[] keyNonMatchingVCF;
	private StringBuilder results;
	private String options;
	private long keyBps;
	private long testBps;
	private long commonBps;
	private int numberUnfilteredKeyVariants = 0;
	private int numberFilteredKeyVariants = 0;
	private ArrayList<Float> tprAL = new ArrayList<Float>();
	private ArrayList<Float> fdrAL = new ArrayList<Float>();
	private ArrayList<ScoredCalls> scoredCallsAL = new ArrayList<ScoredCalls>();
	private String[] fixedFdrLines = null;
	//double[] fixedFdr = new double[] {0.15, 0.05, 0.01};
	double[] fixedFdr = new double[] {0.2, 0.15, 0.10};
	private String headerLine = "QUALThreshold\tNumMatchTest\tNumNonMatchTest\tFDR= nonMatchTest/(matchTest+nonMatchTest)\tdecreasingFDR\tRecall TPR= matchTest/totalKey\tFPR= nonMatchTest/totalKey\tPrecision PPV= matchTest/(matchTest+nonMatchTest)\tF-score= harmonicMean(Precision, Recall)";

	//constructor
	public VCFComparator(String[] args){
		//start clock
		long startTime = System.currentTimeMillis();

		//process args
		processArgs(args);

		for (int i=0; i< testVcfFiles.length; i++){
			vcfTest = testVcfFiles[i];
			results = new StringBuilder();

			//add options to results?
			printOptions();
			if (saveDirectory != null) results.append(options);

			//parse vcf file
			System.out.println("Parsing and filtering variant data for common interrogated regions...");
			if (parseFilterFiles() == false) continue;

			//if useVQSLOD
			if (useVQSLOD){
				try {
					testParser.setRecordScore("VQSLOD");
				} catch (Exception e) {
					System.err.println("\nProblem parsing VQSLOD from INFO? Was the GATK ApplyRecalibration run on your vcf file?\n");
					e.printStackTrace();
					System.exit(0);
				}
			}
			else {
				//set record QUAL score as thresholding score for roc curve data
				testParser.setRecordQUALAsScore();
			}

			//compare calls in common interrogated regions
			System.out.println("Comparing calls...");
			thresholdAndCompareCalls();
			
			//make scoredCalls
			scoredCallsAL.add(new ScoredCalls(Misc.removeExtension(vcfTest.getName())));

			//call after comparing!
			if (saveDirectory != null) {
				printParsedDatasets();
				printIntersectingDatasets();
				printFixedFdrLines();
			}

		}
		
		//print out composite scoredCalls?
		if (saveDirectory !=null && scoredCallsAL.size() >1) printScoredCalls();

		//finish and calc run time
		double diffTime = ((double)(System.currentTimeMillis() -startTime))/1000;
		System.out.println("\nDone! "+Math.round(diffTime)+" seconds\n");
	}

	private void printScoredCalls() {
		ScoredCalls[] sc = new ScoredCalls[scoredCallsAL.size()];
		scoredCallsAL.toArray(sc);
		
		//find max row length
		int maxLength = 0;
		for (ScoredCalls s: sc){
			if (s.fdr.length > maxLength) maxLength = s.fdr.length;
		}
		
		//make writer
		String filter = "All.xls";
		if (removeSNPs) filter = "NonSNP.xls";
		else if (removeNonSNPs) filter = "SNP.xls";
		File f = new File (saveDirectory, "fdrTprSummary"+filter);
		PrintWriter out;
		
		try {
			out = new PrintWriter( new FileWriter(f));
			
			//print header
			out.print("dFDR\t");
			out.print(sc[0].name);
			for (int i=1; i< sc.length; i++){
				out.print("\tdFDR\t");
				out.print(sc[i].name);
			}
			out.println();
			
			//print first row with 1 for fdr
			out.print("1.0\t");
			out.print(sc[0].tpr[0]);
			for (int i=1; i< sc.length; i++){
				out.print("\t1.0\t");
				out.print(sc[i].tpr[0]);  
			}
			out.println();

			//print data
			//for each row
			for (int i=0; i< maxLength; i++){
				
				//print first sample
				if (i>= sc[0].fdr.length) out.print("\t");
				else {
					out.print(sc[0].fdr[i]);
					out.print("\t");
					out.print(sc[0].tpr[i]);
				}
				//for each sample
				for (int j=1; j< sc.length; j++){
					//past length?
					if (i>= sc[j].fdr.length) out.print("\t\t");
					else {
						out.print("\t");
						out.print(sc[j].fdr[i]);
						out.print("\t");
						out.print(sc[j].tpr[i]);
					}
				}
				out.println();
			}

			
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		
		
	}
	

	public void thresholdAndCompareCalls(){	
		
		//clear old results
		tprAL.clear();
		fdrAL.clear();
		fixedFdrLines = new String[3];

		//intersect and split test into matching and non matching
		intersectVCF();
		
		//printout scores for matches
		saveMatchingScores();

		//sort by score smallest to largest
		Arrays.sort(testNonMatchingVCF, new ComparatorVCFRecordScore());
		Arrays.sort(testMatchingVCF);

		results.append(headerLine);
		results.append("\n");

		//first do without thresholds
		float oldScore = 0;
		if (testNonMatchingVCF.length !=0) oldScore = testNonMatchingVCF[0].getScore();
		int numNonMatchesTest = testNonMatchingVCF.length;
		int numMatches = testMatchingVCF.length;
		float oldFDR = ((float)numNonMatchesTest)/((float)(numMatches + numNonMatchesTest));
		String res = formatResults(Float.MIN_NORMAL, numberFilteredKeyVariants, oldFDR, numMatches, numNonMatchesTest);
		scoreFixedFdrs(oldFDR, res);
		results.append(res.toString());
		results.append("\n");

		//for each score in the nonMatching
		for (int i=0; i< testNonMatchingVCF.length; i++){
			float score = testNonMatchingVCF[i].getScore();
			if (score == oldScore) continue;
			numNonMatchesTest = testNonMatchingVCF.length - i;
			numMatches = countNumberMatches(score);
			float fdr = ((float)numNonMatchesTest)/((float)(numMatches + numNonMatchesTest));
			if (fdr < oldFDR) oldFDR = fdr;
			res = formatResults(score, numberFilteredKeyVariants, oldFDR, numMatches, numNonMatchesTest);
			results.append(res.toString());
			results.append("\n");
			if (numNonMatchesTest == 0 || numMatches == 0) break;
			oldScore = score;
			scoreFixedFdrs(oldFDR, res);
		}

		if (saveDirectory == null) System.out.println("\n"+results);
	}

	private void scoreFixedFdrs(float oldFDR, String resultsLine) {
		double rounded = Num.round(oldFDR, 2);
		if (fixedFdrLines[0] == null && rounded <= fixedFdr[0]) fixedFdrLines[0]= resultsLine;
		if (fixedFdrLines[1] == null && rounded <= fixedFdr[1]) fixedFdrLines[1]= resultsLine;
		if (fixedFdrLines[2] == null && rounded <= fixedFdr[2]) fixedFdrLines[2]= resultsLine;
	}

	private void saveMatchingScores() {
		try {
			if (saveDirectory != null && vcfBedKey == null){
				File matchingScores = new File (saveDirectory, "matchingQualScores"+Misc.removeExtension(vcfTest.getName())+".txt.gz");
				Gzipper out = new Gzipper(matchingScores);
				out.println("#KeyQual\t#TestQual\tKeyCoordinates");
				for (int i=0; i< testMatchingVCF.length; i++){
					out.print(testMatchingVCF[i].getKey().getQuality());
					out.print("\t");
					out.print(testMatchingVCF[i].getTest().getQuality());
					out.print("\t");
					out.println(testMatchingVCF[i].getKey().getChromosome()+":"+testMatchingVCF[i].getKey().getPosition());
				}
				out.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		} 
	}

	private int countNumberMatches(float score) {
		for (int i=0; i< testMatchingVCF.length; i++){
			if (testMatchingVCF[i].getScore() >= score){
				return testMatchingVCF.length - i;
			}
		}
		return 0;
	}
	
	private class ScoredCalls{
		String name;
		float[] tpr;
		float[] fdr;
		
		public ScoredCalls(String name){
			this.name = name;
			tpr = Num.arrayListOfFloatToArray(tprAL);
			fdr = Num.arrayListOfFloatToArray(fdrAL);
		}
	}

	public String formatResults(float threshold, float totalKey, float ratchetFDR, float intTest, float nonIntTest){
		StringBuilder sb = new StringBuilder();
		//threshold
		if (threshold == Float.MIN_NORMAL) sb.append("none");
		else sb.append(threshold);
		sb.append("\t");
		sb.append((int)intTest); sb.append("\t");
		sb.append((int)nonIntTest); sb.append("\t");
		//fdr nonIntTest/totalTest
		sb.append(nonIntTest/(nonIntTest + intTest)); sb.append("\t");
		//ratchet fdr (always decreasing or the prior FDR)
		sb.append(ratchetFDR); sb.append("\t");
		fdrAL.add(ratchetFDR);
		//tpr intTest/totalKey
		float tpr = intTest/totalKey;
		tprAL.add(tpr);
		sb.append(intTest/totalKey); sb.append("\t");
		//fpr nonIntTest/totalKey
		sb.append(nonIntTest/totalKey); sb.append("\t");
		//ppv intTest/totalTest
		float ppv = intTest/(nonIntTest + intTest);
		sb.append(ppv); sb.append("\t");
		//Recall, TRUTH.TP / (TRUTH.TP + TRUTH.FN), same as tpr
		//Precision, QUERY.TP / (QUERY.TP + QUERY.FP)
		//F-score harmonic mean of tpr (recall) and ppv (precision)
		double hm = Num.harmonicMean(new double[] {tpr, ppv});
		sb.append((float)hm);
		return sb.toString();
	}

	public void intersectVCF(){
		//set all records to fail
		if (keyParser != null) keyParser.setFilterFieldOnAllRecords(VCFRecord.FAIL);
		testParser.setFilterFieldOnAllRecords(VCFRecord.FAIL);

		ArrayList<VCFMatch> matches = new ArrayList<VCFMatch>();
		ArrayList<VCFRecord> testNonMatches = new ArrayList<VCFRecord>();

		//for each test record
		for (String chr: testParser.getChromosomeVCFRecords().keySet()){
			boolean keyFound = true;
			VCFLookUp test = testParser.getChromosomeVCFRecords().get(chr);
			
			//vcf key?
			if (keyParser != null){
				VCFLookUp key = keyParser.getChromosomeVCFRecords().get(chr);
				if (key == null)  keyFound = false;
				else countMatches(key, test, matches, testNonMatches);
			}
			//nope bed key
			else {
				 IntervalTree<RegionScoreText> treeKey = keyIntervalTrees.get(chr);
				 if (treeKey == null) keyFound = false;
				 else countMatches(treeKey, test, matches, testNonMatches);
			}
			if (keyFound == false) {
				//add all test to nonMatch
				for (VCFRecord r: test.getVcfRecord()) testNonMatches.add(r);
			}
			
			
		} 
		
		//set arrays
		testMatchingVCF = new VCFMatch[matches.size()];
		testNonMatchingVCF = new VCFRecord[testNonMatches.size()];
		matches.toArray(testMatchingVCF);
		testNonMatches.toArray(testNonMatchingVCF);

		//split key into match and nonMatch
		if (keyParser != null){
			ArrayList<VCFRecord> keyNonMatches = new ArrayList<VCFRecord>();
			for (VCFRecord v : keyParser.getVcfRecords()){
				if (v.getFilter().equals(VCFRecord.FAIL)) keyNonMatches.add(v);
			}
			keyNonMatchingVCF = new VCFRecord[keyNonMatches.size()];
			keyNonMatches.toArray(keyNonMatchingVCF);
		}
	}

	private String[] splitBedKeyIntoMatchNonMatch() {
		//collect matches
		HashSet<String> matches = new HashSet<String>();
		for (int i=0; i< testMatchingVCF.length; i++){
			String chr = testMatchingVCF[i].getTest().getChromosome();
			matches.add(testMatchingVCF[i].getRegionKey().getBedLine(chr));
		}
		StringBuilder hits = new StringBuilder();
		StringBuilder miss = new StringBuilder();
		for (String chr: keyBedCalls.keySet()){
			RegionScoreText[] regions = keyBedCalls.get(chr);
			for (RegionScoreText r: regions){
				String bed = r.getBedLine(chr);
				if (matches.contains(bed)) {
					hits.append(bed);
					hits.append("\n");
				}
				else {
					miss.append(bed);
					miss.append("\n");
				}
			}
		}
		return new String[]{hits.toString(), miss.toString()};
	}

	public void countMatches(VCFLookUp key, VCFLookUp test, ArrayList<VCFMatch> matches, ArrayList<VCFRecord> nonMatches){
		
		int[] posTest = test.getBasePosition();
		VCFRecord[] vcfTest = test.getVcfRecord();
		
		//for each test record 
		for (int i=0; i< vcfTest.length; i++){
			
			//fetch records from key
			int start = posTest[i];
			int stop = posTest[i]+1;
			
			//fuzz it?
			if (indelBpPad != 0){
				start = start - indelBpPad;
				if (start < 0) start = 0;
				stop+= indelBpPad;
			}
					
			//fetch the key records that intersect
			VCFRecord[] matchingKey = key.fetchVCFRecords(start, stop);	
			
			//no records found
			if (matchingKey == null) {
				nonMatches.add(vcfTest[i]);
			}
			else {				
				//for each key that matches
				boolean matchFound = false;
				boolean indelFound = false;
				for (int x=0; x< matchingKey.length; x++){	
					//check to see if it matches
					if (requireAltMatch == false || vcfTest[i].matchesAlternateAlleleGenotype(matchingKey[x], requireGenotypeMatch)) {
						matchingKey[x].setFilter(VCFRecord.PASS);
						vcfTest[i].setFilter(VCFRecord.PASS);
						matches.add(new VCFMatch(matchingKey[x], vcfTest[i]));
						matchFound = true;
						break;
					}
					//is it an indel?
					if (indelFound == false && matchingKey[x].isSNP() == false) indelFound = true;
				}
				//do a fuzzy indel match?
				if (matchFound == false && indelBpPad !=0 && indelFound){
					//flip all to match, should really only be one.
					for (VCFRecord k: matchingKey){
						k.setFilter(VCFRecord.PASS);
						vcfTest[i].setFilter(VCFRecord.PASS);
						matches.add(new VCFMatch(k, vcfTest[i]));
						matchFound = true;
					}
					
				}
				if (matchFound == false) {					
					nonMatches.add(vcfTest[i]);
				}
			}
		}
	}
	
	public void countMatches(IntervalTree<RegionScoreText> key, VCFLookUp test, ArrayList<VCFMatch> matches, ArrayList<VCFRecord> testNonMatches) {
		//for each record in the test
		VCFRecord[] vcfTest = test.getVcfRecord();
		for (int i=0; i< vcfTest.length; i++){
			//fetch records from key
			int size = vcfTest[i].getAlternate().length;
			if (size < vcfTest[i].getReference().length()) size = vcfTest[i].getReference().length();
			ArrayList<RegionScoreText> matchingKey = key.search(vcfTest[i].getPosition(), vcfTest[i].getPosition()+size+2);
			
			//no records found
			int numKey = matchingKey.size();			
			if (numKey ==0) testNonMatches.add(vcfTest[i]);
			else {				
				//for each match
				boolean matchFound = false;
				for (int x=0; x< numKey; x++){	
					//get type 0 snp, 1 insertion, 2 deletion
					int keyType = fetchType(matchingKey.get(x).getText());
					//check to see if it matches
					if (vcfTest[i].matchesVariantType(keyType)){
						vcfTest[i].setFilter(VCFRecord.PASS);
						matches.add(new VCFMatch(matchingKey.get(x), vcfTest[i]));
						matchFound = true;
						break;
					}
				}
				if (matchFound == false) testNonMatches.add(vcfTest[i]);
			}
		}
	}

	/**
	81_81_SNV          0
	80_65_INS_ACAGGA   1           
	81_15_DEL          2   */
	private int fetchType(String text) {
		if (text.contains("SNV")) return 0;
		if (text.contains("INS")) return 1;
		if (text.contains("DEL")) return 2;
		Misc.printErrAndExit("\nError: failed to parse the variant type (SNV, INS, DEL) from the bed key file?\n");
		return 0;
	}

	public long overlapRegions(){
		commonRegions = new HashMap<String,RegionScoreText[]> ();
		long numberCommonBases = 0;
		for (String chr: testRegions.keySet()){
			//fetch arrays
			RegionScoreText[] key = keyRegions.get(chr);
			if (key == null) continue;
			RegionScoreText[] test = testRegions.get(chr);

			//find last base
			int lastBase = RegionScoreText.findLastBase(test);
			int lastBaseKey = RegionScoreText.findLastBase(key);
			if (lastBaseKey > lastBase) lastBase = lastBaseKey;

			//make booleans, initially all false, flip to true if covered;
			boolean[] keyBases = new boolean[lastBase+1];
			boolean[] testBases = new boolean[lastBase+1];
			for (int i=0; i< key.length; i++){
				for (int j=key[i].getStart(); j < key[i].getStop(); j++) keyBases[j] = true;
			}
			for (int i=0; i< test.length; i++){
				for (int j=test[i].getStart(); j < test[i].getStop(); j++) testBases[j] = true;
			}
			boolean[] good = new boolean[lastBase];
			Arrays.fill(good, true);
			for (int i=0; i<lastBase; i++){
				//both true, then set false, otherwise set true
				if (keyBases[i] == true && testBases[i] == true) good[i] = false;
			}
			//not intergenic, so add one to end.
			int[][] blocks = ExportIntergenicRegions.fetchFalseBlocks(good, 0, 0);
			RegionScoreText[] common = new RegionScoreText[blocks.length];
			for (int i=0; i< blocks.length; i++){
				common[i] = new RegionScoreText(blocks[i][0], blocks[i][1]+1, 0.0f, null);
				numberCommonBases+= common[i].getLength();
			}
			//add to hash
			commonRegions.put(chr, common);
		}
		return numberCommonBases;
	}

	public boolean parseFilterFiles(){

		//key regions
		if (keyRegions== null){
			keyRegions = Bed.parseBedFile(bedKey, true, true);
			keyBps = RegionScoreText.countBases(keyRegions);
		}

		String res = keyBps +"\tInterrogated bps in key\n";
		results.append(res);

		//same interrogated regions?
		if (bedKey.toString().equals(bedTest.toString())){
			testRegions = keyRegions;
			testBps = keyBps;
			commonRegions = keyRegions;
			commonBps = keyBps;
		}
		
		//test regions
		if (testRegions == null){
			testRegions = Bed.parseBedFile(bedTest, true, true);
			testBps = RegionScoreText.countBases(testRegions);
		}

		res = testBps +"\tInterrogated bps in test\n";
		results.append(res);

		//find common intersected regions common
		if (commonRegions == null) commonBps = overlapRegions();

		res = commonBps +"\tInterrogated bps in common\n";
		results.append(res);

		//parse key variants, either from a bed file or from a vcf file
		if (vcfBedKey != null && keyBedCalls == null){
			
			/*  Parsing something like this:
			15	76070871	76070873	54_30_SNV	0.642857143	.
			16	19179925	19179928	54_87_INS_CTTTTG	0.382978723	.
			3	141622491	141622496	54_25_DEL	0.683544304	.
			*/
			keyBedCalls = Bed.parseBedFile(vcfBedKey, true, true);
			//remove snp or non snp?
			if (removeSNPs || removeNonSNPs) removeSelectVariantTypeFromKeyBed();
			//count unfiltered
			for (RegionScoreText[] r : keyBedCalls.values()) numberUnfilteredKeyVariants+= r.length;
			//filter
			numberFilteredKeyVariants = filterKeyBedCalls();
			//create interval trees
			createIntervalTreesForBedCalls();
		}
		else if (keyParser == null && keyBedCalls == null){			
			keyParser = new VCFParser(vcfKey, true, true, false);		
			if (removeNonPass){				
				keyParser.setFilterFieldPeriodToTextOnAllRecords(VCFRecord.PASS);
				keyParser.filterVCFRecords(VCFRecord.PASS);
			}			
			keyParser.appendChrFixMT();			
			if (removeSNPs) keyParser.removeSNPs();
			if (removeNonSNPs) keyParser.removeNonSNPs();
			numberUnfilteredKeyVariants = keyParser.getVcfRecords().length;	
			if (numberUnfilteredKeyVariants == 0) Misc.printErrAndExit("\nNo key variants passing filters? Aboring.\n");
			keyParser.filterVCFRecords(commonRegions);
			numberFilteredKeyVariants = keyParser.getVcfRecords().length;
		}
		res = numberUnfilteredKeyVariants +"\tKey variants\n";
		results.append(res);
		
		res = numberFilteredKeyVariants +"\tKey variants in shared regions\n";
		results.append(res);

		if (numberFilteredKeyVariants == 0) {
			System.out.println(results);
			IO.el("\nNo key variants in shared regions? Skipping.\n");
			return false;
		}
		
		//calc Ti/Tv for key if in vcf format
		if (keyParser != null) res = keyParser.calculateTiTvRatio() +"\tShared key variants Ti/Tv\n";
		results.append(res);
		
		//parse test variants!
		testParser = new VCFParser(vcfTest, true, true, useVQSLOD);
		
		if (removeNonPass){
			testParser.setFilterFieldPeriodToTextOnAllRecords(VCFRecord.PASS);
			testParser.filterVCFRecords(VCFRecord.PASS);
		}
		testParser.appendChrFixMT();
		if (removeSNPs) testParser.removeSNPs();
		if (removeNonSNPs) testParser.removeNonSNPs();
		res = testParser.getVcfRecords().length +"\tTest variants\n";
		results.append(res);
		if (testParser.getVcfRecords().length == 0){
			System.out.println(results);
			IO.el("\nNo test vcf records found? Skipping.\n");
			return false;
		}
		testParser.filterVCFRecords(commonRegions);
		res = testParser.getVcfRecords().length +"\tTest variants in shared regions\n";
		results.append(res);
		if (testParser.getVcfRecords().length == 0){
			System.out.println(results);
			IO.el("\nNo test variants in shared regions? Skipping.\n");
			return false;
		}
		res = testParser.calculateTiTvRatio() +"\tShared test variants Ti/Tv\n";
		results.append(res);
		results.append("\n");
		return true;
		
	}

	/**Removes SNV or DEL, INS keys.*/
	private void removeSelectVariantTypeFromKeyBed() {
		ArrayList<String> toRemove = new ArrayList<String>();
		for (String chrom: keyBedCalls.keySet()){
			ArrayList<RegionScoreText> good = new ArrayList<RegionScoreText>();
			for (RegionScoreText var : keyBedCalls.get(chrom)){
				boolean snp = var.getText().contains("SNV");
				if (snp) {
					if (removeSNPs == false) good.add(var);
				}
				else {
					if (removeNonSNPs == false) good.add(var);
				}
			}
			if (good.size() !=0){
				RegionScoreText[] regions = new RegionScoreText[good.size()];
				good.toArray(regions);
				keyBedCalls.put(chrom, regions);
			}
			else toRemove.add(chrom);
		}
		//any to remove
		if (toRemove.size() != 0) {
			for (String chr: toRemove) keyBedCalls.remove(chr);
		}
	}

	private void createIntervalTreesForBedCalls() {
		//make HashMap of trees
		keyIntervalTrees = new HashMap<String,IntervalTree<RegionScoreText>>();
		for (String chr : keyBedCalls.keySet()){
			RegionScoreText[] regions = keyBedCalls.get(chr);
			ArrayList<Interval<RegionScoreText>> ints = new ArrayList();
			for (int i =0; i< regions.length; i++) {				
				ints.add(new Interval<RegionScoreText>(regions[i].getStart(), regions[i].getStop(), regions[i]));
			}
			IntervalTree<RegionScoreText> tree = new IntervalTree(ints, false);
			keyIntervalTrees.put(chr, tree);
		}
	}

	//parses key bed variants for those that fall in the common interrogated regions
	private int filterKeyBedCalls() {
		int numGoodRegions = 0;
		HashMap<String,RegionScoreText[]> goodKey = new HashMap<String,RegionScoreText[]>();
		//for each chromosome of common regions
		for (String chr: commonRegions.keySet()){	
			//any keyBedCalls?
			if (keyBedCalls.containsKey(chr) == false) continue;
			//make boolean array representing covered bases
			RegionScoreText[] r = commonRegions.get(chr);
			if (r == null || r.length == 0) continue;
			int lastBase = RegionScoreText.findLastBase(r);
			boolean[] coveredBases = new boolean[lastBase];
			for (int i=0; i< r.length; i++){
				int stop = r[i].getStop();
				for (int j=r[i].getStart(); j< stop; j++){
					coveredBases[j] = true;
				}
			}
			//for each key call, if any base is covered then pass it.
			ArrayList<RegionScoreText> goodRegions = new ArrayList<RegionScoreText>();
			RegionScoreText[] k = keyBedCalls.get(chr);
			for (int i=0; i< k.length; i++){
				int start = k[i].getStart();
				int stop = k[i].getStop();
				//after last base?
				if (start >= lastBase || stop >= lastBase) continue;
				boolean good = false;
				for (int j= start; j< stop; j++){
					if (coveredBases[j]){
						good = true;
						break;
					}
				}
				if (good) goodRegions.add(k[i]);
			}
			//add to good key?
			if (goodRegions.size() != 0) {
				k = new RegionScoreText[goodRegions.size()];
				goodRegions.toArray(k);
				goodKey.put(chr, k);
				numGoodRegions += goodRegions.size();
			}
		}
		//reset key bed calls
		keyBedCalls = goodKey;
		return numGoodRegions;
	}

	public void printParsedDatasets(){
		try {
			String filter = "All_";
			if (removeSNPs) filter = "NonSNP_";
			else if (removeNonSNPs) filter = "SNP_";

			//print common regions
			String bedKeyName = Misc.removeExtension(bedKey.getName());
			String bedTestName = Misc.removeExtension(bedTest.getName());
			File commonBed = new File (saveDirectory, "shared_"+bedKeyName+"_"+bedTestName+".bed.gz");
			Gzipper bed = new Gzipper(commonBed);
			for (String chr: commonRegions.keySet()){
				RegionScoreText[] r = commonRegions.get(chr);
				for (RegionScoreText x : r) bed.println(x.getBedLineJustCoordinates(chr));
			}
			bed.close();

			//sort arrays by chromosome and position
			VCFRecord[][] keyTestMatches = VCFMatch.split(testMatchingVCF);
			Arrays.sort(keyTestMatches[1]);
			Arrays.sort(testNonMatchingVCF);
			if (keyParser != null) {
				Arrays.sort(keyTestMatches[0]);
				Arrays.sort(keyNonMatchingVCF);
			}

			//fetch names for the key and test variant data
			String keyName ;
			if (keyParser != null) keyName = Misc.removeExtension(vcfKey.getName()); 
			else keyName = Misc.removeExtension(vcfBedKey.getName());
			String testName = Misc.removeExtension(vcfTest.getName());
			
			//vcf key
			if (keyParser != null){
				File matchingKey = new File (saveDirectory, "match_"+filter+keyName+"_"+testName+".vcf.gz");
				File noMatchingKey = new File (saveDirectory, "noMatch_"+filter+keyName+"_"+testName+".vcf.gz");
				keyParser.printRecords(keyTestMatches[0],matchingKey);
				keyParser.printRecords(keyNonMatchingVCF,noMatchingKey);
			}
			//region based key
			else {
				String[] hitMiss = splitBedKeyIntoMatchNonMatch();
				if (hitMiss[0].length() !=0){
					File matchingKey = new File (saveDirectory, "match_"+filter+keyName+"_"+testName+".bed");
					IO.writeString(hitMiss[0], matchingKey);
				}
				if (hitMiss[1].length() !=0){
					File noMatchingKey = new File (saveDirectory, "noMatch_"+filter+keyName+"_"+testName+".bed");
					IO.writeString(hitMiss[1], noMatchingKey);
				}
			}

			//vcf Test
			File matchingTest = new File (saveDirectory, "match_"+filter+testName+"_"+keyName+".vcf.gz");
			File noMatchingTest = new File (saveDirectory, "noMatch_"+filter+testName+"_"+keyName+".vcf.gz");
			
			testParser.printRecords(keyTestMatches[1],matchingTest);
			testParser.printRecords(testNonMatchingVCF,noMatchingTest);

			//print results 
			File intersection = new File (saveDirectory, "comparison_"+filter+keyName+"_"+testName+".xls");
			IO.writeString(results.toString(), intersection);

		} catch (Exception e){
			e.printStackTrace();
		}
	}
	
	public void printFixedFdrLines(){
		try {
			String testName = Misc.removeExtension(vcfTest.getName());

			//print results 
			File f = new File (saveDirectory, "fixedFDRLines_"+testName+".xls");
			PrintWriter out = new PrintWriter( new FileWriter(f));
			out.println("TargetFdr\tDataSet\t"+headerLine);
			for (int i=0; i< fixedFdr.length; i++) {
				out.println("tFdr_"+fixedFdr[i]+"_\t"+testName+"\t"+fixedFdrLines[i]);
			}
			out.close();
		} catch (Exception e){
			e.printStackTrace();
		}
	}
	
	
	public void printIntersectingDatasets(){
		//any to print?
		if (testMatchingVCF == null || testMatchingVCF.length ==0) return;
		try {
			//fetch names for the key and test variant data
			String filter = "All_";
			if (removeSNPs) filter = "NonSNP_";
			else if (removeNonSNPs) filter = "SNP_";
			String keyName = Misc.removeExtension(vcfKey.getName()); 
			String testName = Misc.removeExtension(vcfTest.getName());
			File spreadsheet = new File (saveDirectory, "pairedMatches_"+filter+keyName+"_"+testName+".txt.gz");
			Gzipper out = new Gzipper(spreadsheet);
			keyName= keyName+"\t";
			testName= testName+"\t";
			
			//print header
			VCFSample[] samples = null;
			samples = testMatchingVCF[0].getKey().getSample();
			if (samples == null) {
				System.out.println("\tNo samples in key, skipping PrintIntersectingDatasets.");
				out.close();
				spreadsheet.delete();
				return;
			}
			int maxNumSamples = samples.length;
			samples = testMatchingVCF[0].getTest().getSample();
			if (samples == null) {
				System.out.println("\tNo samples in test, skipping PrintIntersectingDatasets.");
				out.close();
				spreadsheet.delete();
				return;
			}
			int numSInT = samples.length;
			if (numSInT> maxNumSamples) maxNumSamples = numSInT;
			out.print("#Dataset\tChr\tPos\tRef\tAlt\tFilter\t");
			for (int i=0; i< maxNumSamples; i++){
				out.print("AF_Sample");
				out.print(i);
				out.print("\tDP_Sample");
				out.print(i);
				out.print("\t");
			}
			out.println("VCFRecord");
			//print records
			for (VCFMatch match: testMatchingVCF){
				out.print(keyName);
				out.println(fetchSpreadSheetRecord(match.getKey(), maxNumSamples));
				out.print(testName);
				out.println(fetchSpreadSheetRecord(match.getTest(), maxNumSamples));
			}
			out.close();
		} catch (Exception e){
			e.printStackTrace();
		}
	}
	
	public static String fetchSpreadSheetRecord(VCFRecord r, int maxNumSamples){
		StringBuilder sb = new StringBuilder();
		//build txt output
		sb.append(r.getChromosome()); sb.append("\t");
		sb.append((r.getPosition()+1)); sb.append("\t");
		sb.append(r.getReference()); sb.append("\t");
		sb.append(Misc.stringArrayToString(r.getAlternate(), ",")); sb.append("\t");
		//filter output
		String filt = Misc.TAB.split(r.getOriginalRecord())[6];
		sb.append(filt); sb.append("\t");
		
		//print samples, some might be blank
		VCFSample[] samples = r.getSample();
		int numSamples = 0;
		if (samples !=null ) numSamples = samples.length;
		for (int i=0; i< maxNumSamples; i++){
			if (samples == null || i >= numSamples) sb.append(".\t.\t");
			else {
				sb.append(samples[i].getAltRatio()); sb.append("\t");
				sb.append(samples[i].getReadDepthDP()); sb.append("\t");
			}
		}
		sb.append(r.getOriginalRecord());
		return sb.toString();
	}



	public static void main(String[] args) {
		if (args.length ==0){
			printDocs();
			System.exit(0);
		}
		new VCFComparator(args);
	}		


	/**This method will process each argument and assign new variables*/
	public void processArgs(String[] args){
		Pattern pat = Pattern.compile("-[a-z]");
		System.out.println("\n"+IO.fetchUSeqVersion()+" Arguments: "+Misc.stringArrayToString(args, " ")+"\n");
		File forExtraction = null;
		for (int i = 0; i<args.length; i++){
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()){
				char test = args[i].charAt(1);
				try{
					switch (test){
					case 'a': vcfKey = new File(args[++i]); break;
					case 'k': vcfBedKey = new File(args[++i]); break;
					case 'b': bedKey = new File(args[++i]); break;
					case 'c': forExtraction = new File(args[++i]); break;
					case 'd': bedTest = new File(args[++i]); break;
					case 'p': saveDirectory = new File(args[++i]); break;
					case 'g': requireGenotypeMatch = true; break;
					case 'f': requireAltMatch = false; break;
					case 's': removeNonSNPs = true; break;
					case 'v': useVQSLOD = true; break;
					case 'n': removeSNPs = true; break;
					case 'e': removeNonPass = true; break;
					case 'i': indelBpPad = Integer.parseInt(args[++i]); break;
					case 'h': printDocs(); System.exit(0);
					default: Misc.printErrAndExit("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Misc.printErrAndExit("\nSorry, something doesn't look right with this parameter: -"+test+"\n");
				}
			}
		}
		//checkfiles
		if (vcfKey == null && vcfBedKey == null) Misc.printErrAndExit("\nError: please provide either a vcf or bed variant key file.\n");
		if (bedKey == null || bedKey.canRead() == false) Misc.printErrAndExit("\nError: please provide a bed file of interrogated regions for the key dataset\n");
		if (bedTest == null) bedTest = bedKey;
		
		//pull files
		if (forExtraction == null || forExtraction.canRead() == false) Misc.printExit("\nError: please provide a test vcf file or directory containing such to compare against the key.\n");
		File[][] tot = new File[3][];
		tot[0] = IO.extractFiles(forExtraction,".vcf");
		tot[1] = IO.extractFiles(forExtraction,".vcf.gz");
		tot[2] = IO.extractFiles(forExtraction,".vcf.zip");
		testVcfFiles = IO.collapseFileArray(tot);
		if (testVcfFiles == null || testVcfFiles.length ==0 || testVcfFiles[0].canRead() == false) Misc.printExit("\nError: cannot find your xxx.vcf(.zip/.gz) file(s)!\n");


		if (saveDirectory != null){
			saveDirectory.mkdirs();
			if (saveDirectory.isDirectory() == false || saveDirectory.exists() == false) Misc.printErrAndExit("\nCannot find or make your save directory?! "+saveDirectory);
		}

		if (removeNonSNPs == true && removeSNPs == true) Misc.printErrAndExit("\nError: looks like you are throwing out all of your data by removing SNPs and non SNPs?! One or the other, not both.\n");
	}	

	private void printOptions() {
		StringBuilder res = new StringBuilder();
		res.append("VCF Comparator Settings:\n\n");
		if (this.vcfBedKey != null) res.append(vcfBedKey.getName()+"\tKey vcf bed file\n");
		else res.append(vcfKey.getName()+"\tKey vcf file\n");
		res.append(bedKey.getName()+"\tKey interrogated regions file\n");
		res.append(vcfTest.getName()+"\tTest vcf file\n");
		res.append(bedTest.getName()+"\tTest interrogated regions file\n");
		if (saveDirectory != null ) res.append(saveDirectory.getName()+"\tSave directory for parsed datasets\n");
		res.append(requireAltMatch+"\tRequire matching alternate bases\n");
		res.append(requireGenotypeMatch+"\tRequire matching genotypes\n");
		res.append(useVQSLOD+"\tUse record VQSLOD score as ranking statistic\n");
		res.append(removeNonPass+ "\tExclude non PASS or . records\n");
		boolean all = removeSNPs == false && removeNonSNPs == false;
		if (all) res.append(all+"\tCompare all variant\n");
		else if (removeSNPs) res.append(removeSNPs+"\tCompare non-SNP variants, not SNPs\n");
		else if (removeNonSNPs) res.append(removeNonSNPs+"\tCompare SNPs, not non-SNP variants\n");
		if (indelBpPad !=0) res.append(indelBpPad+ "\tRelaxing INDEL matches to included all test variants within "+indelBpPad+"bp of a key INDEL variant\n");
		res.append("\n");
		options = res.toString();
		System.out.print(options);
	}

	public static void printDocs(){
		System.out.println("\n" +
				"**************************************************************************************\n" +
				"**                             VCF Comparator : Feb 2021                            **\n" +
				"**************************************************************************************\n" +
				"Compares test vcf file(s) against a gold standard key of trusted vcf calls. Only calls\n" +
				"that fall in the common interrogated regions are compared. WARNING tabix gzipped files\n" +
				"often fail to parse correctly with java. Seeing odd error messages? Try uncompressing.\n"+
				"Be sure a score is provided in the QUAL field.\n\n" +

				"Required Options:\n"+
				"-a VCF file for the key dataset (xxx.vcf(.gz/.zip OK)).\n"+
				"-b Bed file of interrogated regions for the key dataset (xxx.bed(.gz/.zip OK)).\n"+
				"-c VCF file for the test dataset (xxx.vcf(.gz/.zip OK)). May also provide a directory\n" +
				"       containing xxx.vcf(.gz/.zip OK) files to compare.\n"+
				"-d Bed file of interrogated regions for the test dataset (xxx.bed(.gz/.zip OK)).\n"+

				"\nOptional Options:\n"+
				"-k Use a bed file of approx key variants (chr start stop type[#alt_#ref_SNV/INS/DEL]\n"+
				"       instead of a vcf key.\n"+
				"-g Require the genotype to match, defaults to scoring a match when the alternate\n" +
				"       allele is present.\n"+
				"-f Only require the position to match, don't consider the alt base or genotype.\n"+
				"-v Use VQSLOD score as ranking statistic in place of the QUAL score.\n"+
				"-s Only compare SNPs, defaults to all.\n"+
				"-n Only compare non SNPs, defaults to all.\n"+
				"-p Provide a full path directory for saving the parsed data. Defaults to not saving.\n"+
				"-e Exclude test and key records whose FILTER field is not . or PASS. Defaults to\n" +
				"       scoring all.\n"+
				"-i Relax matches to key INDELs to include all test variants within x bps.\n"+

				"\n"+

				"Example: java -Xmx10G -jar pathTo/USeq/Apps/VCFComparator -a /NIST/NA12878/key.vcf\n" +
				"       -b /NIST/NA12878/regions.bed.gz -c /EdgeBio/Exome/testHaploCaller.vcf.zip\n" +
				"       -d /EdgeBio/Exome/NimbleGenExomeV3.bed -g -v -s -e -p /CompRes/ \n\n"+

		"**************************************************************************************\n");

	}
}
