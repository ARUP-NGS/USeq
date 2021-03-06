package edu.utah.seq.qc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

import util.gen.IO;
import util.gen.Misc;

public class AggregateQCStats {

	//fields
	private File saveDirectory;
	private File[] jsonFiles;
	private String prependString = "";

	private String fastqMatch = "(.+)_FastqCount.json.gz";
	private String saeMatch = "(.+)_SamAlignmentExtractor.json.gz";
	private String mpaMatch = "(.+)_MergePairedAlignments.json.gz";
	private String s2uMatch = "(.+)_Sam2USeq.json.gz";
	private String bcMatch = "(.+)_BamConcordance.json.gz";
	private String aiMatch = "(.+)_AvatarInfo.json.gz";
	private String dupMatch = "(.+)_RemoveDuplicates.json.gz";

	private TreeMap<String, SampleQC> samples;
	private ArrayList<File> bamConcordanceFiles = new ArrayList<File>();
	private HashMap<String, BamConcordanceQC> bamFileNameBCR = null;
	private HashMap<String, JSONObject> avatarInfo = new HashMap<String, JSONObject>();
	private Pattern fastqPattern;
	private Pattern saePattern;
	private Pattern mpaPattern;
	private Pattern s2uPattern;
	private Pattern bcPattern;
	private Pattern aiPattern;
	private Pattern dupPattern;
	
	private boolean debug = false;
	private boolean calcReadCoverage = true;
	private boolean swapExomeForDNA = false;


	//constructors
	public AggregateQCStats(String[] args){

		long startTime = System.currentTimeMillis();
		processArgs(args);

		System.out.print("Loading samples");
		loadSamples();

		System.out.print("\nChecking samples");
		checkSamples();
		
		if (bamConcordanceFiles.size() != 0) parseBamConcordances();

		System.out.println("\nSaving aggregated data...");
		System.out.println("\tStats");
		printStatsTxtSheet();

		if (calcReadCoverage){
			System.out.println("\tRead coverage");
			printReadCoverageTxtSheet();
			System.out.println("\tTargets");
			printTargetsTxtSheet();
		}

		System.out.println("Building html reports...");
		printHtmlTable();
		if (calcReadCoverage) printReadCoverageHtml("Coverage Over Target BPs", "Unique Observation Fold Coverage", "Fraction Target BPs");

		//finish and calc run time
		double diffTime = ((double)(System.currentTimeMillis() -startTime))/1000;
		System.out.println("\nDone! "+Math.round(diffTime)+" Sec\n");
	}

	private void parseBamConcordances() {
		File f = null;
		try {
			bamFileNameBCR = new HashMap<String, BamConcordanceQC>();
			for (int i=0; i< bamConcordanceFiles.size(); i++) {
				f= bamConcordanceFiles.get(i);
				new BamConcordanceQC(f, bamFileNameBCR, swapExomeForDNA);
			}
		} catch (IOException e) {
			e.printStackTrace();
			Misc.printErrAndExit("\nProblem parsing bam concordance files! "+f);
		}

	}

	private void printHtmlTable(){
		try {
			SampleQC sqc = samples.values().iterator().next();
			StringBuilder sb = new StringBuilder();
			sb.append("<html>  \n");
			sb.append("<head>  \n");
			sb.append("    <style> #table_main { width: 100%; height: 100%;} </style>  \n");
			sb.append("<script type='text/javascript' src='https://www.gstatic.com/charts/loader.js'></script>   \n");
			sb.append("<script> \n");
			sb.append("google.charts.load('current', {'packages':['table']}); \n");
			sb.append("google.charts.setOnLoadCallback(drawTable); \n");
			sb.append("function drawTable() { \n");
			sb.append("	var data = new google.visualization.DataTable(); \n");

			//add columns
			sqc.appendHtmlColumns(sb, bamFileNameBCR!=null);

			sb.append("	data.addRows([\n");

			//for each sample
			Iterator<SampleQC> it = samples.values().iterator();
			while (it.hasNext()){
				it.next().appendHtmlDataRow(sb, it.hasNext() == false, bamFileNameBCR);
			}

			sb.append("	]); \n");
			sb.append("	var table = new google.visualization.Table(document.getElementById('table_main')); \n");
			sb.append("	table.draw(data, {title:'Summary Stats', showRowNumber: false, cssClassNames:{headerCell: 'googleHeaderCell'}}); \n");
			sb.append("	window.addEventListener('resize', function() { \n");
			sb.append("		table.draw(rcData, options);  \n");
			sb.append("        }, false); \n");
			sb.append("} \n");
			sb.append("</script>  \n");
			sb.append("</head> \n");
			sb.append("<body> \n");
			sb.append("<div id='table_main'></div><body> \n");
			sb.append("</body> \n");
			sb.append("</html> \n");

			File html = new File(saveDirectory, prependString+"qcReport_Stats.html");
			IO.writeString(sb.toString(), html);
		} catch (IOException e){
			e.printStackTrace();
			Misc.printErrAndExit("\nProblem saving html table");
		}
	}


	private void printReadCoverageHtml(String title, String hAxis, String vAxis){
		StringBuilder sb = new StringBuilder();
		sb.append("<html> \n");
		sb.append("<head> \n");
		sb.append("    <style> #gChart { width: 100%; height: 100%;} </style> \n");
		sb.append("<script type='text/javascript' src='https://www.gstatic.com/charts/loader.js'></script> \n");
		sb.append("<script> \n");
		sb.append("google.charts.load('current', {'packages':['corechart', 'line']}); \n");
		sb.append("google.charts.setOnLoadCallback(drawChart); \n");

		sb.append("function drawChart() { \n");
		sb.append("	var rcData = new google.visualization.DataTable(); \n");

		//how many X axis numbers 0x, 1x, 2x, 3x, 4x,...
		int max25 = 0;
		//for each sample
		for (SampleQC s: samples.values()){
			int hit25 = s.whenHit25ReadCoverage();
			if (hit25> max25) max25= hit25;
		}
		if (max25 < 10) max25 = 10;

		//add 1st index X column
		sb.append("\trcData.addColumn('number','X')\n");
		//add one for each sample
		for (SampleQC s: samples.values()){
			sb.append("\trcData.addColumn('number','"+ s.getSampleName() +"')\n");
		}

		//add the data rows
		sb.append("\trcData.addRows([\n");
		int last = max25-1;
		for (int i=0; i< max25; i++){
			sb.append("\t\t\t[");
			sb.append(i);
			for (SampleQC s: samples.values()){
				sb.append(",");
				float[] f = s.getFractionTargetBpsWithIndexedCoverage();
				if (i>= f.length) sb.append(0f);
				else sb.append(f[i]);
			}
			if (i != last )sb.append("],\n");
			else sb.append("]");
		}
		sb.append("	]);\n");

		//add options
		sb.append("\tvar options={ title:'"+title+"', hAxis:{title:'"+hAxis+"'}, vAxis:{title:'"+vAxis+"'}}; \n");
		sb.append("\tvar chart = new google.visualization.LineChart(document.getElementById('gChart')); \n");

		sb.append("\tchart.draw(rcData, options); \n");

		sb.append("\twindow.addEventListener('resize', function() { \n");
		sb.append("\t\tchart.draw(rcData, options); \n");
		sb.append("\t\t}, false); \n");
		sb.append("\t} \n");

		sb.append("</script>  \n");
		sb.append("</head> \n");
		sb.append("<body> \n");
		sb.append("<div id='gChart'></div><body> \n");
		sb.append("</body> \n");
		sb.append("</html>  \n");
		sb.append(" \n");

		File html = new File(saveDirectory, prependString+"qcReport_ReadCoverage.html");
		IO.writeString(sb.toString(), html);
	}


	private void printStatsTxtSheet() {
		StringBuilder sb = new StringBuilder();

		//add header
		SampleQC sqc = samples.values().iterator().next();
		sb.append(sqc.fetchTabbedHeader(avatarInfo.size() !=0, bamFileNameBCR != null) ); sb.append("\n");

		//for each sample
		for (SampleQC s: samples.values()){

			//attempt to parse id from the first field in the sampleName XXX_XXX to pull avatarInfo
			if (avatarInfo.size() !=0){
				String[] tokens = Misc.UNDERSCORE.split(s.sampleName);
				JSONObject jo = avatarInfo.get(tokens[0]);
				if (jo != null) s.setAvatarInfo(jo);
				else s.setAvatarInfo(new JSONObject());
			}
			
			String bamFileName = s.getMpaBamFileName();
			if (bamFileName != null && bamFileNameBCR != null) {
				BamConcordanceQC bc = bamFileNameBCR.get(bamFileName);
				sb.append(s.fetchTabbedLine(bc));
				if (bc == null) IO.pl("WARNING: Null bam concordance found for "+bamFileName);
			}
			else sb.append(s.fetchTabbedLine(null));
			sb.append("\n");
		}
		sb.append("\n");

		//add thresholds
		sb.append("Thresholds:\n");
		sb.append(sqc.fetchThresholds("\t", "\n"));
		sb.append("\n\n");

		//add descriptions 
		sb.append("Descriptions:\n");
		sb.append(sqc.fetchDescriptions("", "\t", "\n", bamFileNameBCR != null));

		File txt = new File(saveDirectory, prependString+ "qcReport_Stats.xls");
		IO.writeString(sb.toString(), txt);
	}

	private void printReadCoverageTxtSheet() {
		StringBuilder sb = new StringBuilder();

		//read coverage
		sb.append("Coverage\t");
		sb.append(fetchTabbedSampleNames());
		sb.append("\n");

		//how many X axis numbers 0x, 1x, 2x, 3x, 4x,...
		int max25 = 0;
		//for each sample
		for (SampleQC s: samples.values()){
			int hit25 = s.whenHit25ReadCoverage();
			if (hit25> max25) max25= hit25;
		}
		if (max25 < 11) max25 = 11;

		//start at 1 to skip 0x coverage and graph better in excel
		for (int i=1; i< max25; i++){
			sb.append(i);
			for (SampleQC s: samples.values()){
				sb.append("\t");
				float[] f = s.getFractionTargetBpsWithIndexedCoverage();
				if (i>= f.length) sb.append(0f);
				else sb.append(f[i]);
			}
			sb.append("\n");
		}

		File txt = new File(saveDirectory, prependString+ "qcReport_ReadCoverage.xls");
		IO.writeString(sb.toString(), txt);
	}

	private void printTargetsTxtSheet() {
		if (debug) System.out.println();
		SampleQC sqc = samples.values().iterator().next();
		StringBuilder sb = new StringBuilder();

		//read coverage
		sb.append("Target\t");
		sb.append(fetchTabbedSampleNames());
		sb.append("\n");

		//check the samples and make sure the regions are identical, except for the coverage count, watch the order this changes!
		String[] regions = splitOutCoor(sqc.getExonicMedianPerBpCoverage())[0];
		String[][] counts = new String[samples.size()][regions.length];
		int counter = 0;
		for (SampleQC s: samples.values()){
			String[][] testRegions = splitOutCoor(s.getExonicMedianPerBpCoverage());
			if (debug) System.out.println(s.sampleName+"\t"+testRegions[0].length +"\t"+s.getTargetRegionsFileNameSAE()+"\t"+s.getTargetRegionsFileNameS2U());
			if (regions.length != testRegions[0].length) Misc.printErrAndExit("\nERROR: the number of regions differ between samples, top?! Rerun with -v to debug. "+
					regions.length+" vs "+ testRegions[0].length+ " Sample "+s.sampleName);
			for (int i=0; i< regions.length; i++) if (regions[i].equals(testRegions[0][i])== false) Misc.printErrAndExit("\nERROR: the regions differ between samples?! Rerun with -v to debug. "+
					regions[i]+" vs "+testRegions[0][i]+ " Sample "+s.sampleName);
			//add em
			counts[counter++] = testRegions[1];
		}

		//write em out
		for (int i=0; i< regions.length; i++){
			sb.append(regions[i]);
			//for each sample
			for (int j=0; j< counts.length; j++){
				sb.append("\t");
				sb.append(counts[j][i]);
			}
			sb.append("\n");
		}

		File txt = new File(saveDirectory, prependString+ "qcReport_PerRegionCoverage.xls");
		IO.writeString(sb.toString(), txt);
	}

	/*"19:60391-60514 8.0", "19:60655-60778 53.0" */
	private String[][] splitOutCoor(String[] regions){
		//use hash to sort!
		TreeMap<String, String> coorVal = new TreeMap<String,String>();
		for (int i=0; i< regions.length; i++){
			String[] tokens = Misc.WHITESPACE.split(regions[i]);
			coorVal.put(tokens[0], tokens[1]);
		}
		//convert to array
		String[] coor = new String[regions.length];
		String[] values = new String[regions.length];
		int i=0;
		for (String key: coorVal.keySet()){
			coor[i] = key;
			values[i] = coorVal.get(key);
			i++;
		}
		return new String[][]{coor, values};
	}


	private String fetchTabbedSampleNames() {
		ArrayList<String> al = new ArrayList<String>();
		for (SampleQC s: samples.values()){
			al.add(s.getSampleName());
		}
		return Misc.stringArrayListToString(al, "\t");
	}

	private void loadSamples() {
		//make hash of name and sampleQC
		samples = new TreeMap<String, SampleQC>();

		//make patterns
		fastqPattern = Pattern.compile(fastqMatch, Pattern.CASE_INSENSITIVE);
		saePattern = Pattern.compile(saeMatch, Pattern.CASE_INSENSITIVE);
		mpaPattern = Pattern.compile(mpaMatch, Pattern.CASE_INSENSITIVE);
		s2uPattern = Pattern.compile(s2uMatch, Pattern.CASE_INSENSITIVE);
		bcPattern = Pattern.compile(bcMatch, Pattern.CASE_INSENSITIVE);
		aiPattern = Pattern.compile(aiMatch, Pattern.CASE_INSENSITIVE);
		dupPattern = Pattern.compile(dupMatch, Pattern.CASE_INSENSITIVE);
		
		//for each json file
		Matcher mat = null;
		for (File j: jsonFiles){
			System.out.print(".");

			//bam concordance?
			mat = bcPattern.matcher(j.getName());
			if (mat.matches()) bamConcordanceFiles.add(j);

			//avatar info?
			else {
				mat = aiPattern.matcher(j.getName());
				if (mat.matches()) {
					JSONObject jo = new JSONObject(IO.loadFile(j, " ", true));
					avatarInfo.put(mat.group(1), jo);
				}
				//must be a sample
				else {
					//fetch name and type
					String[] nameType = parseSampleName(j.getName());
					if (nameType == null) IO.pl("\nWarning, no match, skipping json file -> "+j);
					else {
						//fetch SampleQC
						SampleQC sqc = samples.get(nameType[0]);
						if (sqc == null){
							sqc = new SampleQC(nameType[0], calcReadCoverage, swapExomeForDNA);
							samples.put(nameType[0], sqc);
						}
						sqc.loadJson(j, nameType[1]);
					}
				}
			}
		}
	}

	private void checkSamples() {
		SampleQC test = null;
		//for every sample make sure all four json files were parsed
		for (SampleQC s : samples.values()){
			System.out.print(".");
			if (test == null) test = s;
			else {
				//check thresholds
				if (test.checkThresholds(s) == false){
					String tn = test.getSampleName();
					String sn = s.getSampleName();
					System.out.println("\nProblem!\n"+tn+" AS\t" +test.getAlignmentScoreThreshold());
					System.out.println(tn+" MQ\t" +test.getMappingQualityThreshold());
					System.out.println(sn+" AS\t" +s.getAlignmentScoreThreshold());
					System.out.println(sn+" MQ\t" +s.getMappingQualityThreshold());

					Misc.printErrAndExit("\nERROR: this sample's AS, MQ, or AS proc settings differ? Are you changing thresholds between runs? "+s.getSampleName());
				}
				//check json files
				if (test.checkJsonFiles(s) == false) {
					Misc.printErrAndExit("\nERROR: this sample is missing one or more of the required four xxx.json.gz files from the FastaCounter, SAE, MPA, S2U apps. "+s.getSampleName());
				}
			}
		}

	}


	private String[] parseSampleName(String name) {

		Matcher mat = fastqPattern.matcher(name);
		if (mat.matches()) return new String[]{mat.group(1), "fastq"};

		mat = saePattern.matcher(name);
		if (mat.matches()) return new String[]{mat.group(1), "sae"};

		mat = mpaPattern.matcher(name);
		if (mat.matches()) return new String[]{mat.group(1), "mpa"};

		mat = s2uPattern.matcher(name);
		if (mat.matches()) return new String[]{mat.group(1), "s2u"};
		
		mat = dupPattern.matcher(name);
		if (mat.matches()) return new String[]{mat.group(1), "dup"};

		return null;
	}

	public static void main(String[] args) {
		if (args.length == 0){
			printDocs();
			System.exit(0);
		}
		new AggregateQCStats(args);
	}		


	/**This method will process each argument and assign new variables*/
	public void processArgs(String[] args){
		Pattern pat = Pattern.compile("-[a-z]");
		String useqVersion = IO.fetchUSeqVersion();
		File forExtraction = null;
		System.out.println("\n"+useqVersion+" Arguments: "+ Misc.stringArrayToString(args, " ") +"\n");
		for (int i = 0; i<args.length; i++){
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()){
				char test = args[i].charAt(1);
				try{
					switch (test){
					case 'j': forExtraction = new File(args[++i]); break;
					case 'r': saveDirectory = new File(args[++i]); break;
					case 'f': fastqMatch = args[++i]; break;
					case 's': saeMatch = args[++i]; break;
					case 'm': mpaMatch = args[++i]; break;
					case 'u': s2uMatch = args[++i]; break;
					case 'd': dupMatch = args[++i]; break;
					case 'p': prependString = args[++i]; break;
					case 'v': debug = true; break;
					case 'e': swapExomeForDNA = true; break;
					case 'c': calcReadCoverage = false; break;
					default: Misc.printErrAndExit("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Misc.printErrAndExit("\nSorry, something doesn't look right with this parameter: -"+test+"\n");
				}
			}
		}
		//save directory
		if (saveDirectory == null) Misc.printErrAndExit("\nPlease provide a directory to save the results.\n");
		saveDirectory.mkdirs();

		//pull json files
		if (forExtraction == null || forExtraction.canRead() == false) Misc.printExit("\nError: please provide a directory to recurse through to find xxx.json.gz files.\n");
		jsonFiles = IO.fetchFilesRecursively(forExtraction, ".json.gz");

		if (jsonFiles == null || jsonFiles.length ==0 || jsonFiles[0].canRead() == false) Misc.printExit("\nError: cannot find any xxx.json.gz file(s) to parse!\n");

	}	

	public static void printDocs(){
		System.out.println("\n" +
				"**************************************************************************************\n" +
				"**                            Aggregate QC Stats: Nov 2020                          **\n" +
				"**************************************************************************************\n" +
				"Parses and aggregates alignment quality statistics from json files produced by the\n"+
				"SamAlignmentExtractor, MergePairedAlignments, Sam2USeq, BamConcordance, Fastq, and\n"+
				"RemoveDuplicates rules in the TNRunner workflows.\n"+

				"\nOptions:\n"+
				"-j Directory containing xxx.json.gz files for parsing. Recurses through all other\n"+
				"      directories contained within.\n" +
				"-r Results directory for writing the summary xls spreadsheets for graphing.\n"+

				"\nDefault Options:\n"+
				"-f FastqCount regex for parsing sample name, note the name must be identical across\n"+
				"   the json files, defaults to (.+)_FastqCount.json.gz, case insensitive.\n"+
				"-s SAE regex, defaults to (.+)_SamAlignmentExtractor.json.gz\n"+
				"-m MPA regex, defaults to (.+)_MergePairedAlignments.json.gz\n"+
				"-u S2U regex, defaults to (.+)_Sam2USeq.json.gz\n"+
				"-b BC regex, defaults to (.+)_BamConcordance.json.gz\n"+
				"-b DUP regex, defaults to (.+)_RemoveDuplicates.json.gz\n"+
				"-p String to prepend onto output file names.\n"+
				"-c Don't calculate detailed region read coverage statistics, saves memory and time.\n"+
				"-v Print verbose debugging output.\n"+
				"-e Replace Exome with DNA in all file reference names.\n"+
				"\n"+

				"Example: java -Xmx1G -jar pathToUSeq/Apps/AggregateQCStats -j . -r QCStats/ -p TR774_ \n\n" +

				"**************************************************************************************\n");

	}

	public boolean isCalcReadCoverage() {
		return calcReadCoverage;
	}	
}
