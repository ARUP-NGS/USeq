package edu.utah.seq.vcf.xml.foundation;

import java.io.*;
import java.util.regex.*;
import edu.utah.seq.vcf.VCFParser;
import util.gen.*;
import java.util.*;

/**
 * Takes a patient vcf file parsed from a Foundation xml report and compares it to a vcf generated by reprocessing the raw data.
 * Writes out a final arbitrated vcf containing all the Foundation calls plus non duplicate recall variants with no FILTER flags.
 * 
 * @author david.nix@hci.utah.edu 
 **/
public class FoundationVcfComparator {

	//user defined fields
	private File foundationVcf = null;
	private File recallVcf = null;
	private File mergedVcf = null;
	private SimpleVcf[] fVcfs;
	private SimpleVcf[] rVcfs;	
	private int bpPaddingForOverlap = 2;
	private boolean noModifyFoundation = true;
	private boolean excludeFoundationContig = false;
	private boolean appendChr = false;
	private boolean insertMockSample = false;

	//counters
	private int numberShortFoundation = 0;
	private int numberOtherFoundation = 0;
	private int numberRecall = 0;
	private int numberExactMatches = 0;
	private int numberFoundationWithOnlyOverlap = 0;
	private int numberModifiedFoundationCalls = 0;
	private int numberFoundationWithNoMatch = 0;
	private int numberPassingRecallWithNoMatch = 0;
	
	private ArrayList<SimpleVcf> vcfToPrint = new ArrayList<SimpleVcf>();
	private ArrayList<String> headerLines = new ArrayList<String>();
	
	
	//constructors
	public FoundationVcfComparator(String[] args){
		try {
			long startTime = System.currentTimeMillis();
			processArgs(args);

			//load vcf files
			fVcfs = load(foundationVcf, excludeFoundationContig);
			rVcfs = load(recallVcf, false);
			numberRecall = rVcfs.length;

			compareVcfs();

			processFoundationVcfs();
			
			processRecallVcfs();
			
			printVcfs();
			
			printStats();

			//finish and calc run time
			double diffTime = ((double)(System.currentTimeMillis() -startTime))/60000;
			System.out.println("\nDone! "+Math.round(diffTime)+" Min\n");

		} catch (Exception e) {
			e.printStackTrace();
			Misc.printErrAndExit("\nProblem running FoundationXml2Vcf app!");
		}
	}

	private void printStats() {
		System.out.println("\nComparator stats:");
		System.out.println( numberRecall +"\t# Recall variants");
		System.out.println( numberShortFoundation +"\t# Short Foundation variants");
		System.out.println( numberOtherFoundation +"\t# Other Foundation variants");
		System.out.println( numberExactMatches +"\t# Short with an exact match");
		System.out.println( numberFoundationWithOnlyOverlap +"\t# Short with overlap recal variants");
		if (noModifyFoundation) System.out.println( numberModifiedFoundationCalls +"\t# Short recommended for modification");
		else System.out.println( numberModifiedFoundationCalls +"\t# Short modified using overlapping recal variant info");
		System.out.println( numberFoundationWithNoMatch +"\t# Short with no match"); 
		System.out.println( numberPassingRecallWithNoMatch +"\t# Passing recall variants with no Short match");
	}

	private void printVcfs() {
		//sort vcf
		SimpleVcf[] vcf = new SimpleVcf[vcfToPrint.size()];
		vcfToPrint.toArray(vcf);
		Arrays.sort(vcf);
		
		try {
			Gzipper out = new Gzipper(mergedVcf);
			//add in format for mock?
			String sampleId= Misc.removeExtension(mergedVcf.getName());
			if (insertMockSample) {
				headerLines.add(0,"#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t"+sampleId);
				headerLines.add("##FORMAT=<ID=SID,Number=1,Type=String,Description=\"SampleID_RecordNumber\">");
			}
			
			//fetch merged header
			String[] header = mergeHeaders(headerLines);
			
			for (String h: header) out.println(h);
			
			int counter=0;
			for (SimpleVcf v: vcf) {
				if (v.getFilter().toLowerCase().contains("fail") == false) {
					if (insertMockSample) {
						out.println(v.getVcfLine()+"\tSID\t"+sampleId+"_"+counter);
						counter++;
					}
					else out.println(v.getVcfLine());
				}
			}
			
			
			
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
			Misc.printErrAndExit("\nERROR: problem writing out the merged vcf file. "+mergedVcf);
		}
	}

	private void processRecallVcfs() {
		//for each Recall vcf, call this after processing the Foundation vcfs, skip those with a fail filter field
		for (SimpleVcf r:rVcfs){
			//print it?
			if (r.isPrint() && r.getFilter().toLowerCase().contains("fail") == false) {
				//mark Filter NR not reported
				r.appendFilter("NR");
				vcfToPrint.add(r);
				numberPassingRecallWithNoMatch++;
			}
		}
	}

	
	/**Merges header lines eliminating duplicates.  Does a bad ID name collision checking, silently keeps first one. 
	 * Returns null if CHROM lines differ. */
	public static String[] mergeHeaders(ArrayList<String> header) {
		
		LinkedHashSet<String> other = new LinkedHashSet<String>();
		LinkedHashSet<String> contig = new LinkedHashSet<String>();
		LinkedHashSet<String> info = new LinkedHashSet<String>();
		LinkedHashSet<String> filter = new LinkedHashSet<String>();
		LinkedHashSet<String> format = new LinkedHashSet<String>();
		TreeSet<String> source = new TreeSet<String>();
		String chromLine = null;

		for (String h: header){
			h=h.trim();
			if (h.startsWith("##contig")){
				if (contig.contains(h) == false) contig.add(h);
			}
			else if (h.startsWith("##INFO")){
				if (info.contains(h) == false) info.add(h);
			}
			else if (h.startsWith("##FILTER")){
				if (filter.contains(h) == false) filter.add(h);
			}
			else if (h.startsWith("##FORMAT")){
				if (format.contains(h) == false) format.add(h);
			}
			else if (h.startsWith("##source=")){
				source.add(h);
			}
			else if (h.startsWith("#CHROM")) {
				if (chromLine == null) chromLine = h;
				//skip this check, else if (chromLine.equals(h) == false) Misc.printErrAndExit("\nERROR: chrom lines differ!\n");;
			}
			else if (other.contains(h) == false) {
				other.add(h);
			}
		}


		//add in filter lines
		filter.add(SimpleVcf.ncFilter);
		filter.add(SimpleVcf.nrFilter);
		filter.add(SimpleVcf.mdFilter);
		
		//add info lines
		info.add(SimpleVcf.infoRAF);

		//remove ID dups from contig, filter, format, info
		ArrayList<String> contigAL = VCFParser.mergeHeaderIds(contig);
		ArrayList<String> filterAL = VCFParser.mergeHeaderIds(filter);
		ArrayList<String> formatAL = VCFParser.mergeHeaderIds(format);
		ArrayList<String> infoAL = VCFParser.mergeHeaderIds(info);

		ArrayList<String> lines = new ArrayList<String>();
		for (String s : other) lines.add(s);
		for (String s : source) lines.add(s);
		for (String s : contigAL) lines.add(s);
		for (String s : filterAL) lines.add(s);
		for (String s : infoAL) lines.add(s);
		for (String s : formatAL) lines.add(s);
		if (chromLine != null) lines.add(chromLine);

		return Misc.stringArrayListToStringArray(lines);
	}



	private void processFoundationVcfs() {
		//for each Foundation record
		for (SimpleVcf f: fVcfs){

			//not a short? just save it
			if (f.isShortVariant() == false) {
				vcfToPrint.add(f);
				numberOtherFoundation++;
				continue;
			}

			numberShortFoundation++;

			//exact match? 
			if (f.getMatch() != null) {
				//exact match then just print it
				f.appendRAF(f.getMatch());
				f.appendID(f.getMatch());
				vcfToPrint.add(f);
				numberExactMatches++;
				f.getMatch().setPrint(false);
				continue;
			}

			//So no exact match any overlap?
			if (f.getOverlap().size()!=0){
				//always print the foundation vcf record with a NC FILTER field, not confirmed.
				//question is what to do about the overlapping records? print with NR FILTER field, not reported by foundation?
				numberFoundationWithOnlyOverlap++;
				
				//more than one overlap? print foundation and the multiple with NC and NR's
				if (f.getOverlap().size()!=1){
					//System.err.println("Multiple overlap. Printing the Foundation and Recall variants:");
					//System.err.println("F:\t"+f.getOriginalRecord());
					//for (SimpleVcf r: f.getOverlap()) System.err.println("R:\t"+r.getOriginalRecord());
					f.appendFilter("NC");
				}
				
				//ok so only one overlap, do the types match?
				else {
					int lenFRef = f.getRef().length();
					int lenFAlt = f.getAlt().length();
					SimpleVcf r = f.getOverlap().get(0);
					int lenRRef = r.getRef().length();
					int lenRAlt = r.getAlt().length();
					
					//types match and it's a good recal variant, modify the foundation call and print, don't print the recal variant
					if (lenFRef == lenRRef && lenFAlt == lenRAlt && r.getFilter().toLowerCase().contains("fail") == false){
						if (noModifyFoundation){
							System.err.println("WARNING: One overlap and types match, recommend modifying the Foundation record. Will print both with no chr, pos, alt, ref modifications.");
							f.appendFilter("NC");
							System.err.println("R:\t"+r.getOriginalRecord());
							System.err.println("F:\t"+f.getOriginalRecord());
							numberModifiedFoundationCalls++;
						}
						else {
							System.err.println("WARNING: One overlap and types match thus MODIFYING the Foundation pos, ref, alt info and printing it. Not printing the recall.");
							f.swapInfoWithOverlap(r);
							f.appendFilter("MD");
							System.err.println("R:\t"+r.getOriginalRecord());
							System.err.println("F:\t"+f.getOriginalRecord());
							System.err.println("M:\t"+f.getVcfLine());
							numberModifiedFoundationCalls++;
							//set recall to not print
							r.setPrint(false);
						}
					}
					//types don't match so print foundation and recal
					else {
						//System.err.println("One overlap, but diff types. Printing Foundation and Recall vars.");
						f.appendFilter("NC");
					}
				}
				//in all cases print the foundation var
				vcfToPrint.add(f);
				continue;
			}
			
			//No exact or overlap, flag and print
			System.err.println("WARNING: No match to this Foundation variant.");
			numberFoundationWithNoMatch++;
			f.appendFilter("NC");
			vcfToPrint.add(f);
			System.err.println("F:\t"+f.getVcfLine());
		}
	}

	



	private void compareVcfs() {
		//slow comparator, could do many things to speed up...
		for (int i=0; i< fVcfs.length; i++){
			SimpleVcf f = fVcfs[i];
			//is it a short variant?
			if (f.isShortVariant() == false) continue;

			for (int j=0; j< rVcfs.length; j++){
				SimpleVcf r = rVcfs[j];
				if (f.compareToExact(r)){
					if (f.getMatch() !=null || r.getMatch() != null) Misc.printErrAndExit("\nERROR: more than one exact match found for \n"+f+"\n"+r);
					f.setMatch(r);
					r.setMatch(f);
				}
				else if (f.compareToOverlap(r)){
					f.getOverlap().add(r);
					r.getOverlap().add(f);
				}
			}
		}

	}



	private SimpleVcf[] load(File vcf, boolean excludeContig) {
		HashSet<String> uniqueKeys = new HashSet<String>();
		String[] lines = IO.loadFileIntoStringArray(vcf);
		ArrayList<SimpleVcf> al = new ArrayList<SimpleVcf>();
		for (String v: lines){
			if (v.startsWith("#") == false) {
				if (appendChr && v.startsWith("chr") == false) v = "chr"+v;
				SimpleVcf sv = new SimpleVcf(v, bpPaddingForOverlap);
				String key = sv.getChr()+sv.getPos()+sv.getRef()+"_"+sv.getAlt();
				//must watch for duplicates in foundation data
				if (uniqueKeys.contains(key) == false) {
					uniqueKeys.add(key);
					al.add(sv);
				}
			}
			else {
				if (excludeContig){
					if (v.startsWith("##contig") == false) headerLines.add(v);
				}
				else headerLines.add(v);
			}
		}
		SimpleVcf[] svs = new SimpleVcf[al.size()];
		al.toArray(svs);
		return svs;
	}



	public static void main(String[] args) {
		if (args.length ==0){
			printDocs();
			System.exit(0);
		}
		new FoundationVcfComparator(args);
	}		

	/**This method will process each argument and assign new varibles*/
	public void processArgs(String[] args){
		Pattern pat = Pattern.compile("-[a-z]");
		String useqVersion = IO.fetchUSeqVersion();
		String source = useqVersion+" Args: "+ Misc.stringArrayToString(args, " ");
		System.out.println("\n"+ source +"\n");
		for (int i = 0; i<args.length; i++){
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()){
				char test = args[i].charAt(1);
				try{
					switch (test){
					case 'f': foundationVcf = new File(args[++i]); break;
					case 'r': recallVcf = new File(args[++i]); break;
					case 'm': mergedVcf = new File(args[++i]); break;
					case 'k': noModifyFoundation = false; break;
					case 'c': appendChr = true; break;
					case 'v': insertMockSample = true; break; 
					case 'e': excludeFoundationContig = true; break;
					default: Misc.printErrAndExit("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Misc.printErrAndExit("\nSorry, something doesn't look right with this parameter: -"+test+"\n");
				}
			}
		}

		//check files
		if (foundationVcf == null || recallVcf == null || foundationVcf.canRead()== false || recallVcf.canRead()== false) Misc.printErrAndExit("\nError: cannot find both of your vcf files to compare?!\n");
		if (mergedVcf == null) Misc.printErrAndExit("\nError: please provide a named file for writing the merged vcf!\n");


	}	

	public static void printDocs(){
		System.out.println("\n" +
				"**************************************************************************************\n" +
				"**                           Foundation Vcf Comparator: Nov 2019                    **\n" +
				"**************************************************************************************\n" +
				"FVC compares a Foundation vcf generated with the FoundationXml2Vcf to a recalled vcf.\n"+
				"Exact recall vars are so noted and removed. Foundation vcf with no exact but one\n"+
				"overlapping record can be merged with -k. Be sure to vt normalize each before running.\n"+
				"Recall variants failing FILTER are not saved.\n"+

				"\nOptions:\n"+
				"-f Path to a FoundationOne vcf file, see the FoundationXml2Vcf app.\n"+
				"-r Path to a recalled snv/indel vcf file.\n"+
				"-m Path to named vcf file for saving the results.\n"+
				"-c Append chr if absent in chromosome name.\n"+
				"-e Exclude Foundation ##contig header lines.\n"+
				"-v Insert mock sample info for VarSeq filtering.\n"+
				"-k Attempt to merge Foundation records that overlap a recall and are the same type. \n"+
				"     Defaults to printing both.\n"+

				"\nExample: java -Xmx2G -jar pathToUSeq/Apps/FoundationVcfComparator -f /F1/TRF145.vcf\n" +
				"     -r /F1/TRF145_recall.vcf.gz -e -c -m /F1/TRF145_merged.vcf.gz -k \n\n" +

				"**************************************************************************************\n");

	}



}
