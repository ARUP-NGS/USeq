package edu.utah.ames.bioinfo;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * This class contains methods to automatically align fastq sequences after they come out of the HiSeq Pipeline.  
 * All the necessary input data is contained in the fresh data report that comes out of the HiSeq Pipeline.
 * Files are run individually. A new analysis report is created in GNomEx (linked to experiment numbers) for all 
 * samples of the same request number. The requester is notified via email when jobs start and finish. Bisulphite 
 * files are split into smaller chunks using the FileSplitter app and aligned individually. Results from all steps
 * in the bisulfite alignment process are appended to the same GNomEx analysis report. Tomato will only email the user 
 * if there are exceptions and/or job failures (-ef option, i.e. #e email@nobody.com -ef) 
 * 
 * @author darren.ames@hci.utah.edu
 *
 */

public class AutoNovoaligner {

	//fields
	private static String requestNum; 
	private static String analysisNumber;
	private static String myEmail = "darren.ames@hci.utah.edu"; //TODO change this when I know it works correctly
	private static String freshDataReport = "/home/sbsuser/Pipeline/AutoAlignReport/reports/autoalign_*.txt";
	private static String novoindexNames = "/home/sbsuser/Pipeline/AutoAlignReport/reports/autoAlignerData/novoindexNameTable.txt";
	private static String parsedFreshDataReports = "/home/sbsuser/Pipeline/AutoAlignReport/reports/processedReports/";
	private static String requestYear;
	private static String tomatoJobDir = "/tomato/job/autoaligner/alignments/";
	private String smtpHostName = "mail.inscc.utah.edu"; //default
	static String EMAILREGEX = "^#e\\s+(.+@.+)"; //email address pattern
	static String LABNAMEREGEX = "\\w+\\s\\w+(?=\\WLab)"; //matches first and last name of lab using positive look
	private static String reportsDir = "/home/sbsuser/Pipeline/AutoAlignReport/reports/";
	private HashMap<String, String> genomeIndex;
	private static boolean doNotAlign = false;
	private static boolean isSmallRNA = false;
	private static boolean hasNovoindex = true;

	//constructor
	public AutoNovoaligner(String[] args) {	
		processArgs(args);
	}

	public static void main(String[] args) throws Exception {
		
		//check for args
		if (args.length == 0) {
			printDocs();
			System.exit(0);
		}
		AutoNovoaligner an = new AutoNovoaligner(args);
		an.parseFreshDataReport();
	}

	/**
	 * Loads a list of novoindices into a hash. First column (build_sequencingApplicationCode[_radius?]) 
	 * is the key, abbreviated index name used by Tomato in second column is the value.
	 * @param s
	 * @return
	 * @throws IOException
	 */
	public HashMap<String,String> loadNovoindexList() throws IOException{
		
		//make new hashmap
		HashMap<String,String> indices = new HashMap<String,String>(10000);
		try{
			//read in the file of index codes and names
			BufferedReader br = new BufferedReader(new FileReader(novoindexNames));
			String line;
			String[] keyValue;
			//split file on tabs
			while ((line = br.readLine()) != null){
				keyValue = line.split("\t");
				//put the key/value pairs in the hashmap
				indices.put(keyValue[0].trim(), keyValue[1].trim());
			}
		} 
		catch(Exception e) {
			System.out.println("Problem loading the Hash");
			e.printStackTrace();
		}
		return indices;
	}

	/**
	 * Parses the fresh data report and populates all the fields of a Sample object.
	 * @throws Exception 
	 */
	public void parseFreshDataReport() throws Exception {
		
		//load novoindex hash
		genomeIndex = loadNovoindexList();
		//create buffered reader to read fresh data report
		BufferedReader br = new BufferedReader(new FileReader(freshDataReport));
		String line;
		//skip first line in file
		br.readLine();
		//make hashmap
		HashMap<String,String> hm = new HashMap<String,String>();

		//loop the read block until all lines in the file are read
		while ((line = br.readLine()) != null) {
			
			//split contents of tab-delimited file 
			String dataValue[] = line.split("\t");
			
			//check for correct number of columns in fresh data report
			if (dataValue.length < 15) {
				continue;
			}
			else {
				Sample s = new Sample(dataValue);
				//parse out "Lab" from lab name string
				Pattern p = Pattern.compile(".+(?=Lab)");
				Matcher m = p.matcher(s.getLab());
				if (m.find()) {
					s.setLab(m.group());
				}
				
				String genome = dataValue[s.genomeIndex];
				//skip samples without organism or index info
				if (s.getOrganism().toString().equals("Unknown") || s.getOrganism().toString().equals("Other") || 
						s.getOrganism().toString().equals(null) || s.getGenome().toString().equals("None") || 
						s.getGenome().toString().equals(null)) {
					doNotAlign = true;
					continue;
				}
				else {
					s.setGenome(genome);
					//call downstream methods to match samples with the correct novoindex
					this.setCondSeqAppCode(s);
				}
				//check for matching novoindex for the sample before creating new analysis report 
				if (!(s.getNovoindex() == null)) {
					//get new analysis report number for samples that have a matching novoindex
					analysisNumber = hm.get(s.getRequestNumber());
					if (analysisNumber == null) {
						//populate with request numbers
						analysisNumber = this.getAnalysisNumber(s);
						s.setAnalysisNumber(analysisNumber);
						hm.put(s.getRequestNumber(), analysisNumber);
					}
					else {
						s.setAnalysisNumber(analysisNumber);
					}
				}
			}
		}
		//close the reader
		br.close();
		
		//move the parsed fresh data report to the processedReports folder
		File file = new File(freshDataReport);
		//boolean success = file.renameTo(new File(parsedFreshDataReports + freshDataReport.substring(41)));
		boolean success = file.renameTo(new File(parsedFreshDataReports + freshDataReport.substring(47)));
		if (!success) {
			System.out.println("Error moving parsed fresh data report to the parsed directory.");
		}
	}
	
	/**
	 * Runs an external shell script, writing params to stdin and collecting output in stderr/stdout. 
	 * The method grabs a new analysis number in GNomEx for each unique request number that has 
	 * a matching novoindex in the tomato/data directory. New analysis reports and their corresponding
	 * experiment numbers are linked. 
	 * @param s
	 * @throws IOException
	 */
	public String getAnalysisNumber(Sample s) throws IOException {
		
		String line;
		InputStream stderr = null;
		InputStream stdout = null;
		String analysisNum = null;

		//feed input params into string array
		String cmd[] = {"./httpclient_create_analysis.sh", "-properties", "gnomex_httpclient.properties", 
				"-serverURL", "https://bioserver.hci.utah.edu", "-name", s.getProjectName(), 
				"-folderName", "novoalignments", "-lab", s.getLab(), "-organism", s.getOrganism(), 
				"-genomeBuild", s.getBuildCode(), "-analysisType", "Alignment", "-seqLane", s.getSequenceLaneNumber()};
	
		//launch the script and grab stdin/stdout and stderr
		Process process = Runtime.getRuntime().exec(cmd, null, new File("/Users/darren/Desktop/novoalignerTestDir/create_analysis/"));
		stderr = process.getErrorStream();
		stdout = process.getInputStream();

		//clean up if any output in stdout
		BufferedReader brCleanup = new BufferedReader(new InputStreamReader(stdout));
		
		//fetch output analysis number
		while (((line = brCleanup.readLine()) != null)) {
			System.out.println("[stdout] " + line);
			if (line.indexOf("idAnalysis=") > 0) {
				//grab the new analysis number in output
				String matchANum = "([A][0-9]+)";
				Pattern p = Pattern.compile(matchANum);
				Matcher m = p.matcher(line);
				if (m.find()) {
					//assign analysis number to var
					analysisNum = m.group();
					s.setAnalysisNumber(analysisNum);
					//System.out.println(s.getAnalysisNumber());
				}
			}
			//clean this up so it reports an exception and then continues
		}
		brCleanup.close();
		//call method that creates the cmd.txt file
		this.createCmdFile(s);
		return analysisNum;
	}

	/**
	 * Sets condensed sequencing application codes for matching sample to appropriate novoindex
	 * @param s
	 * @throws Exception 
	 */
	public void setCondSeqAppCode(Sample s) throws Exception {
		
		//check to see if small RNA, and if so, set the boolean flag
		if (s.getSequencingApplicationCode().toString().equals("SMRNASEQ")) {
			isSmallRNA = true;
		}
		//set condensed sequencing application codes if other (kinda) redundant names are used
		if (s.getSequencingApplicationCode().toString().equals("APP2") || 
				s.getSequencingApplicationCode().toString().equals("APP3") || 
				s.getSequencingApplicationCode().toString().equals("MRNASEQ") || 
				s.getSequencingApplicationCode().toString().equals("SMRNASEQ")) {
			s.setSequencingApplicationCode("_MRNASEQ_");
		}
		else if (s.getSequencingApplicationCode().toString().equals("APP1") || 
				s.getSequencingApplicationCode().toString().equals("TDNASEQ") || 
				s.getSequencingApplicationCode().toString().equals("EXCAPNIM") || 
				s.getSequencingApplicationCode().toString().equals("APP4") || 
				s.getSequencingApplicationCode().toString().equals("DNASEQ") || 
				s.getSequencingApplicationCode().toString().equals("APP5") || 
				s.getSequencingApplicationCode().toString().equals("CHIPSEQ")) {
			s.setSequencingApplicationCode("_DNASEQ");
		}
		else if (s.getSequencingApplicationCode().toString().equals("APP6")) {
			s.setSequencingApplicationCode("_BISSEQ");
			
			//send an email to signify that bisulfite sequences are going to be split and aligned
			this.postMail(myEmail, "\nBisulfite alignments", ("\nHello kind Sir, some split bisulfite sequences " +
					"are aligning. Live long and prosper.\n\n"
					+ s.getRequestNumber() + "\n" + s.getSampleID() + "\n" + s.getGenome() + "\n" + s.getBuildCode()), "autoalign@nobody.com");
		}
		parseGenomeIndex(s);
	}

	/**
	 * Parses abbreviated genome name (hg19, mm10...) from larger build string
	 * @param sample
	 * @return
	 * @throws IOException 
	 * @throws Exception 
	 */
	public String parseGenomeIndex(Sample s) throws IOException {
		
		//match only build code in sample genome string
		String BUILDCODE = "\\w+(?=[;,:]\\s+\\w+[;,:])"; 

		//parse build code from genome string
		if (s.getGenome() != null) {
			Pattern p = Pattern.compile(BUILDCODE);
			Matcher m = p.matcher(s.getGenome());
			if (m.find()) {
				s.setBuildCode(m.group());
			}
		} 
		buildShortIndexName(s);
		return s.getBuildCode();
	}

	/**
	 * Builds the shortened novoindex name from data in the fresh data report.
	 * Uses this shortened name as key for matching sample to the associated matching novoindex
	 * name that's recognized by Tomato
	 * @param s
	 * @return
	 * @throws IOException
	 */
	public void buildShortIndexName(Sample s) throws IOException {
		
		//check to see if no build code is specified in the report
		if (s.getBuildCode() == null) {
			doNotAlign = true;
		}
		else {
			//if RNA: build short index name for key in hashmap
			if (s.getSequencingApplicationCode().toString().equals("_MRNASEQ_")) {
				//build the key
				String key = (s.getBuildCode() + s.getSequencingApplicationCode() + s.getNumberOfCycles()); 
				//check hashmap for key (short index name), set index code
				if (genomeIndex.containsKey(key)) {
					s.setIndexCode(key);
					s.setNovoindex(genomeIndex.get(key));
				}
			}
			//if DNA or bisulfite, don't include #cycles in short index name key in hashmap
			else if (s.getSequencingApplicationCode().toString().equals("_BISSEQ") || 
					s.getSequencingApplicationCode().toString().equals("_DNASEQ")) {
				//build the key
				String key = (s.getBuildCode() + s.getSequencingApplicationCode());
				if (genomeIndex.containsKey(key)) {
					s.setIndexCode(key);
					s.setNovoindex(genomeIndex.get(key));
				}
			}
		}
		//createCmdFile(s);
	}

	/**
	 * Checks to see if a directory exists for the request number, and makes a new directory if it doesn't. 
	 * Subdirectories are made for each separate sample ID and populated with cmd.txt file. The cmd.txt file
	 * for bisulfite sequences first contains file splitting parameters, and upon completion, an intermediary
	 * file (bisAlignWait.txt) containing alignment params is moved to each new chunked bisulfite directory, 
	 * renamed cmd.txt and executed.
	 * @param s
	 * @return
	 * @throws IOException
	 */
	public void createCmdFile(Sample s) throws IOException {
		
		String jobDirPath = tomatoJobDir + s.getRequestNumber() + "/" + s.getSampleID();
		try {
			//first make sure an index exists before continuing
			if (!(s.getIndexCode() == null)) {
				//check to see if job dir exists for requestNum
				File dir = new File(jobDirPath);
				//make appropriate new directory and subdirectories
				dir.mkdirs();
				//create cmd.txt file
				FileWriter fw = new FileWriter(jobDirPath + "/" + "cmd.txt");
				BufferedWriter out = new BufferedWriter(fw);
				
				//make bisAlignWait file for bisulfite sequences
				if (s.getSequencingApplicationCode().toString().equals("_BISSEQ")) {
					//make bisAlignWait.txt file that will later be the @align cmd.txt file after file splitting
					FileWriter bisAlignWait = new FileWriter(jobDirPath + "/" + "bisAlignWait.txt");
					BufferedWriter bout = new BufferedWriter(bisAlignWait);
					bout.write(this.getCmdFileMessageBisulfite(s));
					bout.close();
				}
				//write the messages
				out.write(this.getCmdFileMsgGen(s));
				//close output stream
				out.close();
			}
		}
		catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}
	}
	
	/**
	 * Generates the general body of the cmd.txt file using sample-specific params and calls appropriate methods to do 
	 * the actual populating of application-specific params based on whether sequencing application is 
	 * bisulfite, miRNA/small RNA, exome, genomic DNA, or other (mRNA...)
	 * 
	 * @param sample
	 * @return
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	public String getCmdFileMsgGen(Sample s) throws IOException, InterruptedException {
		
		//path for input fastq file
		String inputFile = "/Repository/MicroarrayData/" + s.getRequestYear() + "/" + s.getRequestNumber()
				+ "/Fastq/" + s.getSampleID() + "_*.txt.gz";
		//set string for non-variable part of cmd.txt params
		String msg = "#e " + myEmail + " -ef" + "\n#a " + s.getAnalysisNumber() + "\n## Novoalignments of " 
				+ s.getRequestNumber() + " " + s.getProjectName() + "\n## Aligning to " 
				+ s.getBuildCode() + " for " + s.getRequester() + " in the " + s.getLab() + "Lab " + "\n\n@align -novoalign " + 
				"-g " + s.getNovoindex() + " -i " + inputFile + " -gzip ";
		//command string for splitting bisulfite files and preparing dirs for alignment
		//***TODO substitute requester email for mine when I know it works***
		String bisulfiteMsg = "#e" + myEmail + " -ef" + "\n#a " + s.getAnalysisNumber() + "\n## Splitting bisulfite fastq files of " 
				+ s.getRequestNumber() + " " + s.getProjectName() + " for " + s.getRequester() + " in the "
				+ s.getLab() + "Lab " + "\n\nFileSplitter.jar" + " -f " + inputFile + " -n 100000000 " + "-g"
				+ "\n\nfor i in *_" + s.getSampleID() + "*; do n=${i%_" + s.getSampleID() + "_*}; mkdir $n; " 
				+ "mv $i $n; cp bisAlignWait.txt $n; mv $n/bisAlignWait.txt $n/cmd.txt; touch $n/b; done\n";
		
		//instantiate new StringBuffer object for holding cmd.txt file's body
		StringBuffer sb = new StringBuffer();

		//don't generate cmd.txt file if appropriate novoindex isn't available
		if (!(s.getIndexCode() == null)) {
			//is it be bisulfite? 
			if (s.getSequencingApplicationCode().equalsIgnoreCase("_BISSEQ")) {
				//get the bisulfite alignment params
				sb.append(bisulfiteMsg);
				sb.append(this.getCmdFileMessageBisulfite(s));
			}
			//all right then, how about small RNA?
			else if (s.getSequencingApplicationCode().equalsIgnoreCase("_MRNASEQ_") && 
					(isSmallRNA == true)) {
				sb.append(msg);
				sb.append(this.getCmdFileMessageSmallRNA(s));
			}
			//hmmm, could it be regular mRNA or RNA?
			else if (s.getSequencingApplicationCode().equalsIgnoreCase("_MRNASEQ_") && 
					(isSmallRNA == false)) {
				sb.append(msg);
				sb.append(this.getCmdFileMessageStdParams(s));
			}
			//ok then, it's got to be genomic DNA, mononucleosome or ChIP-Seq
			else if (s.getSequencingApplicationCode().equalsIgnoreCase("_DNASEQ")) {
				sb.append(msg);
				sb.append(this.getCmdFileMessageGenomic(s));
			}
			//nope, it's something freaky that isn't currently supported in AutoNovoaligner 
			else {
				doNotAlign = true;	
			}
		}
		//System.out.println(sb.toString());
		//start the job by creating the b file
		this.startTomatoJob(s);
		return sb.toString();
	}
	/**
	 * exome, genomic DNA, mononucleosome, ChIP alignment params
	 * @param s
	 * @return
	 */
	public String getCmdFileMessageGenomic(Sample s) {
		s.setAlignParams(" [-r None -a AGATCGGAAGAGCACACGTCTGAACTCCAGTCAC " +
				"AGATCGGAAGAGCGTCGTGTAGGGAAAGAGTGTAGATCTCGGTGGTCGCCGTATCATT -H -k]\n");
		return s.getAlignParams();
	}

	/**
	 * bisulfite alignment params
	 * @param s
	 * @return
	 */
	public String getCmdFileMessageBisulfite(Sample s) {
		s.setAlignParams("#e " + myEmail + "\n#a " + s.getAnalysisNumber() + "\n## Novoalignments of " + 
				s.getRequestNumber() + " " + s.getProjectName() + "\n## Aligning to " + s.getBuildCode()
				+ " for " + s.getRequester() + " in the " + s.getLab() + "Lab " + "\n\n@align -novoalign " +
				"[-o SAM -r Random -t 240 -h 120 -b 2] -i *.txt.gz -g " + s.getNovoindex() + " -p bisulphite -gzip\n");
		return s.getAlignParams();	
	}

	/**
	 * small RNA alignment params
	 * @param s
	 * @return
	 */
	public String getCmdFileMessageSmallRNA(Sample s) {
		//includes 3' adapter stripping prior to alignment
		//***this is NOT the standard Illumina Gex Adapter 2 used as default by novoalign
		s.setAlignParams(" [-o SAM -r All 50 -m -a ATCTCGTATGCCGTCTTCTGCTTG -l 15 -t 30]\n");
		return s.getAlignParams();
	}

	/**
	 * standard mRNA alignment params
	 * @param s
	 * @return
	 */
	public String getCmdFileMessageStdParams(Sample s) {
		s.setAlignParams(" [-o SAM -r All 50]\n");
		return s.getAlignParams(); 
	}

	/**
	 * This method creates a new empty "b" file if the file with the same name does 
	 * not already exist. Returns true if the file with the same name did not 
	 * exist and it was created successfully, false otherwise. This starts the 
	 * Tomato job. 
	 */
	public void startTomatoJob(Sample s) {

		//create flag
		boolean bFileCreated = false;

		//create b file to start the jobs
		File file1 = new File(tomatoJobDir + s.getRequestNumber() + "/" + s.getSampleID() + "/" + "b");
		try {
			bFileCreated = file1.createNewFile();
			bFileCreated = true;
		}
		catch (IOException ioe) {
			bFileCreated = false;
			doNotAlign = true;
			System.out.println("Error while creating the new b file: " + s.getSampleID() + "\t" + ioe); 
		}
	}

	/**
	 * Sends an email when bisulfite sequences are being split by FileSplitter
	 * @param recipients
	 * @param subject
	 * @param message
	 * @param from
	 * @throws MessagingException
	 */
	public void postMail(String recipients, String subject, String message, String from) throws MessagingException {

		//set the host smtp address
		Properties props = new Properties();
		props.put("mail.smtp.host", "hci-mail.hci.utah.edu");

		//create some properties and get the default Session
		javax.mail.Session session = javax.mail.Session.getDefaultInstance(props, null);

		//create message
		Message msg = new MimeMessage(session);

		//set the from and to address
		InternetAddress addressFrom = new InternetAddress(from);
		msg.setFrom(addressFrom);
		msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipients, false));

		//optional: can also set custom headers here in the Email if wanted
		//msg.addHeader("MyHeaderName", "myHeaderValue");

		//setting the Subject and Content type
		msg.setSubject(subject);
		msg.setContent(message, "text/plain");
		Transport.send(msg);
	}

	/**
	 * This method will process each argument and assign new variables.
	 * @param args
	 */
	public void processArgs(String[] args) {
	
		Pattern pat = Pattern.compile("-[a-z]");
		String programArgs = Misc.stringArrayToString(args, ",");
		boolean verbose = false;
		if (verbose) System.out.println("\nArguments: " + programArgs + "\n");
		for (int i = 0; i < args.length; i++) {
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()) {
				char test = args[i].charAt(1);
				try {
					switch (test) {
					//case 'i': fastqFileName = new String(args[++i]); break;
					//case 'c': cmdFilePath = new String(args[++i]); break;
					//case 'g': genomeBuild = args[i+1]; i++; break;
					case 'd': reportsDir = new String(args[++i]); break;
					case 'f': freshDataReport = new String(args[++i]); break; 
					//case 'u': submitter = new String(args[++i]); break;
					default: Misc.printErrAndExit("\nProblem--unknown option used!" + mat.group());
					}
				}
				catch (Exception e) {
					Misc.printErrAndExit("\nSorry, something doesn't look right with this parameter: -" + test + "\n");
				}
			}
		}
		//if (versionedGenome == null) Misc.printErrAndExit
		//("\nPlease provide a versioned genome (e.g. H_sapiens_Mar_2006).\n");
		//if (cmdFilePath == null) Misc.printErrAndExit("\nPlease provide full path to cmd.txt file.\n");
		//if (fastqFileName == null) Misc.printErrAndExit("\nPlease provide full path to fastq file(s) to align.\n");
		//if (paramsFile == null) Misc.printErrAndExit("\nPlease provide full path to alignment params file.\n");
		//if (userName == null) Misc.printErrAndExit("\nPlease provide name of user whose sequences you're aligning.\n");

	}

	public static void printDocs() {
		System.out.println("\n" +
				"**********************************************************************************\n" +
				"**                        AutoNovoaligner: Feb 2013                          **\n" +
				"**********************************************************************************\n" + 
				"This class contains methods to automatically align fastq sequences using novoalign\n" +
				"after they come out of the HiSeq Pipeline. All the necessary input data is contained\n" +
				"in the fresh data report that comes out of the HiSeq Pipeline. Files are run individually.\n" +
				"A new analysis report is created in GNomEx (linked to experiment numbers) for all\n" +
				"samples of the same request number. The requester is notified via email when jobs start\n" +
				"and finish (except bisulfite at the moment). Bisulphite files are split into smaller chunks\n" +
				"and aligned individually. Results from all steps in the bisulfite alignment process are\n" +
				"appended to the same GNomEx analysis report. In the case of bisulfite alignments, Tomato\n" +
				"will only email the user if there are exceptions and/or job failures\n (-ef option,\n" +
				"i.e. #e email@nobody.com -ef).\n" +
				
				"Usage:\n\n" +
				"java -jar -Xmx2G pathTo/AutoNovoaligner -d pathTo/jobDir/\n" +
				"**********************************************************************************\n");
	}
}