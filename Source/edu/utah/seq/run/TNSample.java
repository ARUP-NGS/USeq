package edu.utah.seq.run;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import util.gen.IO;
import util.gen.Misc;

public class TNSample {
	
	private String id;
	private File rootDir;
	private TNRunner tnRunner;
	private boolean forceRestart;
	private boolean restartFailedJobs;
	private static final Pattern slurmJobId = Pattern.compile("slurm-(\\d+).out");
	private ArrayList<String> info = new ArrayList<String>();
	private boolean deleteBamConcordance = false;
	
	//Fastq
	private FastqDataset tumorExomeFastq = null;
	private FastqDataset normalExomeFastq = null;
	private FastqDataset tumorTransFastq = null;
	
	//Alignment and passing region bed files
	private File[] tumorExomeBamBedGvcf = null;
	private File[] normalExomeBamBedGvcf = null;
	private File tumorTranscriptomeBam = null;
	
	//Somatic variant Vcf calls
	private File somaticVariants = null;
	
	//Joint genotyping
	private File[] germlineVcf = null;
	
	//Job status
	private boolean failed = false;
	private boolean running = false;
	

	public TNSample(File rootDir, TNRunner tnRunner) throws IOException{
		this.rootDir = rootDir;
		this.tnRunner = tnRunner;
		this.forceRestart = tnRunner.isForceRestart();
		this.restartFailedJobs = tnRunner.isRestartFailed();
		
		//assign id
		id = rootDir.getName();
		info.add("\n"+id);
		
		//look for fastq folders and check they contain 2 xxx.gz files, some may be null
		boolean foundFastq = checkFastq();
		
		//launch available alignments
		if (foundFastq) checkAlignments();
		else return;
		
		//launch t/n somatic exome and copy ratio analysis?
		if (tumorExomeBamBedGvcf != null) somExomeCall();
		
		//annotate somatic vcf?
		if (somaticVariants != null) annotateSomaticVcf();
		
		//bam concordance?
		bamConcordance();
		
		//Annotate normal vcf
		annotateGermlineVcf();
		
		//copy ratio/ number
		copyRatioAnalysis();
		
		//print messages?
		if (failed || tnRunner.isVerbose()) {
			Misc.printArray(info);
			IO.pl("Failures found? "+failed);
			IO.pl("Running jobs? "+running);
		}
	}
	
	public void annotateGermlineVcf() throws IOException {
		//look for genotyped vcf
		File vcf = new File (rootDir, "GermlineVariantCalling/"+id+"_NormalExome/"+id+"_NormalExome_Hg38_JointGenotyped.vcf.gz");
		if (vcf.exists() == false) return;
		germlineVcf = new File[]{vcf, new File(rootDir, "GermlineVariantCalling/"+id+"_NormalExome/"+id+"_NormalExome_Hg38_JointGenotyped.vcf.gz.tbi") };

		info.add("Checking germline variant annotation...");		
		
		//make dir, ok if it already exists
		File jobDir = new File (rootDir.getCanonicalFile(), "GermlineVariantCalling/"+id+"_NormalExomeAnno");
		jobDir.mkdirs();
		
		//any files?
		HashMap<String, File> nameFile = IO.fetchNamesAndFiles(jobDir);
		if (nameFile.size() == 0) launchVariantAnnotation(jobDir, vcf, tnRunner.getGermlineAnnotatedVcfParser());
		
		//OK some files are present
		//COMPLETE
		else if (nameFile.containsKey("COMPLETE")){
			//remove the linked vcfs and the config file
			new File(jobDir, vcf.getName()).delete();
			new File(jobDir, vcf.getName()+".tbi").delete();
			new File(jobDir, "annotatedVcfParser.config.txt").delete();
			info.add("\t\tCOMPLETE "+jobDir);
		}
		
		//force a restart?
		else if (forceRestart || restartFailedJobs && nameFile.containsKey("FAILED")){
			//cancel any slurm jobs and delete the directory
			cancelDeleteJobDir(nameFile, jobDir, info);
			//launch it
			launchVariantAnnotation(jobDir, vcf, tnRunner.getGermlineAnnotatedVcfParser());
		}
		//QUEUED
		else if (nameFile.containsKey("QUEUED")){
			info.add("\t\tQUEUED "+jobDir);
			running = true;
		}
		//STARTED
		else if (nameFile.containsKey("STARTED")) {
			if (checkQueue(nameFile, jobDir, info) == false) failed = true;
			else running = true;
		}
		//FAILED but no forceRestart
		else if (nameFile.containsKey("FAILED")) {
			info.add("\t\tFAILED "+jobDir);
			failed = true;
		}
		//hmm no status files, probably something went wrong on the culster? mark it as FAILED
		else {
			info.add("\t\tMarking as FAILED, no job status files in "+jobDir);
			new File(jobDir, "FAILED").createNewFile();
			failed = true;
		}
	}

	private void bamConcordance() throws IOException {
		info.add("Checking bam concordance...");
		File jobDir = new File (rootDir, "SampleConcordance/"+id+"_BamConcordance");
		
		//Started a new alignment? then delete the concordance if it exists
		if (deleteBamConcordance && jobDir.exists()){
			info.add("\t\tNew alignments started.");
			cancelDeleteJobDir(IO.fetchNamesAndFiles(jobDir), jobDir, info);
			return;
		}
		
		//check to see if the alignments are present, sometimes these datasets are never coming
		boolean goT = false;
		boolean goN = false;
		boolean goTT = false;
		int numExist = 0;
		if (tumorExomeFastq.isFastqDirExists()){
			if (tumorExomeBamBedGvcf != null) goT = true;
			numExist++;
		}
		else goT = true;
		if (normalExomeFastq.isFastqDirExists()){
			if (normalExomeBamBedGvcf != null) goN = true;
			numExist++;
		}
		else goN = true;
		if (tumorTransFastq.isFastqDirExists()){
			if (tumorTranscriptomeBam != null) goTT = true;
			numExist++;
		}
		else goTT = true;
		if (numExist < 2) return;
		if (goT == false || goN == false || goTT == false) return;
		
		//make dir, ok if it already exists
		jobDir.mkdirs();
		
		//any files?
		HashMap<String, File> nameFile = IO.fetchNamesAndFiles(jobDir);
		if (nameFile.size() == 0) launchBamConcordance(jobDir);
		
		//OK some files are present
		//COMPLETE
		else if (nameFile.containsKey("COMPLETE")){
			//remove the linked vcfs and the config file
			removeBamConcordanceLinks(jobDir);
			info.add("\t\tCOMPLETE "+jobDir);
		}
		//force a restart?
		else if (forceRestart || restartFailedJobs && nameFile.containsKey("FAILED")){
			//cancel any slurm jobs and delete the directory
			cancelDeleteJobDir(nameFile, jobDir, info);
			//launch it
			launchBamConcordance(jobDir);
		}
		//QUEUED
		else if (nameFile.containsKey("QUEUED")){
			info.add("\t\tQUEUED "+jobDir);
			running = true;
		}
		//STARTED
		else if (nameFile.containsKey("STARTED")) {
			if (checkQueue(nameFile, jobDir, info) == false) failed = true;
			else running = true;
		}
		//FAILED but no forceRestart
		else if (nameFile.containsKey("FAILED")){
			info.add("\t\tFAILED "+jobDir);
			failed = true;
		}
		//hmm no status files, probably something went wrong on the culster? mark it as FAILED
		else {
			info.add("\t\tMarking as FAILED, no job status files in "+jobDir);
			new File(jobDir, "FAILED").createNewFile();
			failed = true;
		}
	}
	
	
	private void launchBamConcordance(File jobDir) throws IOException {
		info.add("\t\tLaunching "+jobDir);
		running = true;
		
		//want to create/ replace the soft links 
		removeBamConcordanceLinks(jobDir);

		//soft link in the new ones
		createBamConcordanceLinks(jobDir);
		
		//replace any launch scripts with the current
		File shellScript = copyInWorkflowDocs(tnRunner.getBamConcordanceDocs(), jobDir);

		//clear any progress files
		removeProgressFiles(jobDir);

		//squeue the shell script
		new File(jobDir, "QUEUED").createNewFile();
		String jobDirPath = jobDir.getCanonicalPath();
		String[] output = IO.executeViaProcessBuilder(new String[]{"sbatch", "-J", jobDirPath.replace(tnRunner.getPathToTrim(), ""), "-D", jobDirPath, shellScript.getCanonicalPath()}, false);
		for (String o: output) info.add("\t\t"+o);
	}
	
	private void createBamConcordanceLinks(File jobDir) throws IOException{
		File canJobDir = jobDir.getCanonicalFile();
		if (tumorExomeBamBedGvcf != null) {
			File index = fetchBamIndex(tumorExomeBamBedGvcf[0]);
			Files.createSymbolicLink(new File(canJobDir, "tumorExome.bam").toPath(), tumorExomeBamBedGvcf[0].toPath());
			Files.createSymbolicLink(new File(canJobDir, "tumorExome.bai").toPath(), index.toPath());
		}
		if (normalExomeBamBedGvcf != null) {
			File index = fetchBamIndex(normalExomeBamBedGvcf[0]);
			Files.createSymbolicLink(new File(canJobDir, "normalExome.bam").toPath(), normalExomeBamBedGvcf[0].toPath());
			Files.createSymbolicLink(new File(canJobDir, "normalExome.bai").toPath(), index.toPath());
		}
		if (tumorTranscriptomeBam != null) {
			File index = fetchBamIndex(tumorTranscriptomeBam);
			Files.createSymbolicLink(new File(canJobDir, "tumorTranscriptome.bam").toPath(), tumorTranscriptomeBam.toPath());
			Files.createSymbolicLink(new File(canJobDir, "tumorTranscriptome.bai").toPath(), index.toPath());
		}
		//pull in Foundation bams?
		ArrayList<File> toLink = fetchOtherBams();
		if (toLink != null && toLink.size() != 0){
			for (File f: toLink) Files.createSymbolicLink(new File(canJobDir, "F_"+f.getName()).toPath(), f.toPath());
		}
	}
	
	private ArrayList<File> fetchOtherBams() throws IOException {
		HashMap<String, File> otherPatientDatasets = tnRunner.getOtherBams();
		if (otherPatientDatasets != null){
			File matchedPatientDir = otherPatientDatasets.get(id);
			if (matchedPatientDir == null) return null;
			ArrayList<File> otherBams = IO.fetchAllFilesRecursively(matchedPatientDir, ".bam");
			
			//watch out for dups due to linking and failed jobs
			HashMap<String, File> uniqueFiles = new HashMap<String, File>();
			for (File ob: otherBams) uniqueFiles.put(ob.getCanonicalPath(), ob.getCanonicalFile());
			
			ArrayList<File> toLink = new ArrayList<File>();
			for (File ob: uniqueFiles.values()){
				//DNA bam and RNA bam
				if (ob.getName().endsWith("_final.bam") || ob.getName().endsWith("_Hg38.bam")) {
					File index = fetchBamIndex(ob);
					if (index != null){
						toLink.add(ob);
						toLink.add(index);
					}
				}
			}
			return toLink;
		}
		return null;
	}

	private void removeBamConcordanceLinks(File jobDir) throws IOException{
		IO.deleteFiles(jobDir, ".bam");
		IO.deleteFiles(jobDir, ".bai");
	}

	private void annotateSomaticVcf() throws IOException {
		info.add("Checking somatic variant annotation...");		
		
		//make dir, ok if it already exists
		File jobDir = new File (rootDir, "SomaticVariantCalls/"+id+"_IlluminaAnno");
		jobDir.mkdirs();
		
		//any files?
		HashMap<String, File> nameFile = IO.fetchNamesAndFiles(jobDir);
		if (nameFile.size() == 0) launchVariantAnnotation(jobDir, somaticVariants, tnRunner.getSomaticAnnotatedVcfParser());
		
		//OK some files are present
		//COMPLETE
		else if (nameFile.containsKey("COMPLETE")){
			//remove the linked vcfs and the config file
			new File(jobDir, somaticVariants.getName()).delete();
			new File(jobDir, somaticVariants.getName()+".tbi").delete();
			new File(jobDir, "annotatedVcfParser.config.txt").delete();
			info.add("\t\tCOMPLETE "+jobDir);
		}
		
		//force a restart?
		else if (forceRestart  || restartFailedJobs && nameFile.containsKey("FAILED")){
			//cancel any slurm jobs and delete the directory
			cancelDeleteJobDir(nameFile, jobDir, info);
			//launch it
			launchVariantAnnotation(jobDir, somaticVariants, tnRunner.getSomaticAnnotatedVcfParser());
		}
		//QUEUED
		else if (nameFile.containsKey("QUEUED")){
			info.add("\t\tQUEUED "+jobDir);
			running = true;
		}
		//STARTED
		else if (nameFile.containsKey("STARTED")) {
			if (checkQueue(nameFile, jobDir, info) == false) failed = true;
			else running = true;
		}
		//FAILED but no forceRestart
		else if (nameFile.containsKey("FAILED")){
			info.add("\t\tFAILED "+jobDir);
			failed = true;
		}
		//hmm no status files, probably something went wrong on the culster? mark it as FAILED
		else {
			info.add("\t\tMarking as FAILED, no job status files in "+jobDir);
			new File(jobDir, "FAILED").createNewFile();
			failed = true;
		}
	}

	private void somExomeCall() throws IOException {
		info.add("Checking somatic variant calling...");		
		
		//make dir, ok if it already exists
		File somDir = new File (rootDir,"SomaticVariantCalls/"+id+"_Illumina");
		somDir.mkdirs();
		
		//any files?
		HashMap<String, File> nameFile = IO.fetchNamesAndFiles(somDir);
		if (nameFile.size() == 0) launchSomaticVariantCalls(somDir);
		
		//OK some files are present
		//COMPLETE
		else if (nameFile.containsKey("COMPLETE")){
			//find the final vcf file
			File[] vcf = IO.extractFiles(new File(somDir, "Vcfs"), "_final.vcf.gz");
			if (vcf == null || vcf.length !=1) {
				info.add("\tThe somatic variant calling was marked COMPLETE but failed to find the final vcf file in the Vcfs/ in "+somDir);
				failed = true;
				return;
			}
			somaticVariants = vcf[0];
			//remove the linked fastq
			removeSomaticLinks(somDir);
			info.add("\t\tCOMPLETE "+somDir);
		}
		
		//force a restart?
		else if (forceRestart  || restartFailedJobs && nameFile.containsKey("FAILED")){
			//cancel any slurm jobs and delete the directory
			cancelDeleteJobDir(nameFile, somDir, info);
			//launch it
			launchSomaticVariantCalls(somDir);
		}
		//QUEUED
		else if (nameFile.containsKey("QUEUED")){
			info.add("\t\tQUEUED "+somDir);
			running = true;
		}
		//STARTED
		else if (nameFile.containsKey("STARTED")) {
			if (checkQueue(nameFile, somDir, info) == false) failed = true;
			else running = true;
		}
		//FAILED but no forceRestart
		else if (nameFile.containsKey("FAILED")){
			info.add("\t\tFAILED "+somDir);
			failed = true;
		}
		//hmm no status files, probably something went wrong on the culster? mark it as FAILED
		else {
			info.add("\t\tMarking as FAILED, no job status files in "+somDir);
			new File(somDir, "FAILED").createNewFile();
			failed = true;
		}
	}
	
	private void copyRatioAnalysis() throws IOException {
		if (tnRunner.getCopyRatioDocs() == null) return;
		
		//look for genotyped vcf
		File vcf = new File (rootDir, "GermlineVariantCalling/"+id+"_NormalExome/"+id+"_NormalExome_Hg38_JointGenotyped.vcf.gz");
		if (germlineVcf == null || tumorExomeBamBedGvcf == null || normalExomeBamBedGvcf == null) return;
		
		info.add("Checking copy ratio calling...");		
		
		//make dir, ok if it already exists
		File jobDir = new File (rootDir,"CopyRatio");
		jobDir.mkdirs();
		
		//any files?
		HashMap<String, File> nameFile = IO.fetchNamesAndFiles(jobDir);
		if (nameFile.size() == 0) launchCopyRatio(jobDir);
		
		//OK some files are present
		//COMPLETE
		else if (nameFile.containsKey("COMPLETE")){
			//find the final vcf file
			File[] segs = IO.extractFiles(new File(jobDir, "Results"), ".called.seg.xls");
			if (segs == null || segs.length !=1) {
				info.add("\tThe copy ratio calling was marked COMPLETE but failed to find the final seg file in the Results/ dir in "+jobDir);
				failed = true;
				return;
			}
			//remove the linked fastq
			removeCopyRatioLinks(jobDir);
			info.add("\t\tCOMPLETE "+jobDir);
		}
		
		//force a restart?
		else if (forceRestart  || restartFailedJobs && nameFile.containsKey("FAILED")){
			//cancel any slurm jobs and delete the directory
			cancelDeleteJobDir(nameFile, jobDir, info);
			//launch it
			launchCopyRatio(jobDir);
		}
		//QUEUED
		else if (nameFile.containsKey("QUEUED")){
			info.add("\t\tQUEUED "+jobDir);
			running = true;
		}
		//STARTED
		else if (nameFile.containsKey("STARTED")) {
			if (checkQueue(nameFile, jobDir, info) == false) failed = true;
			else running = true;
		}
		//FAILED but no forceRestart
		else if (nameFile.containsKey("FAILED")){
			info.add("\t\tFAILED "+jobDir);
			failed = true;
		}
		//hmm no status files, probably something went wrong on the culster? mark it as FAILED
		else {
			info.add("\t\tMarking as FAILED, no job status files in "+jobDir);
			new File(jobDir, "FAILED").createNewFile();
			failed = true;
		}
	}

	private void launchCopyRatio(File jobDir) throws IOException {
		info.add("\t\tLaunching "+jobDir);
		running = true;
		
		//want to replace the soft links
		createCopyRatioLinks(jobDir);
		
		//replace any launch scripts with the current
		File shellScript = copyInWorkflowDocs(tnRunner.getCopyRatioDocs(), jobDir);
		
		//clear any progress files
		removeProgressFiles(jobDir);
		
		//squeue the shell script
		new File(jobDir, "QUEUED").createNewFile();
		String alignDirPath = jobDir.getCanonicalPath();
		String[] output = IO.executeViaProcessBuilder(new String[]{"sbatch", "-J", alignDirPath.replace(tnRunner.getPathToTrim(), ""), "-D", alignDirPath, shellScript.getCanonicalPath()}, false);
		for (String o: output) info.add("\t\t"+o);
	}
	
	private void createCopyRatioLinks(File jobDir) throws IOException {
		//remove any linked files
		removeCopyRatioLinks(jobDir);
		
		//pull gender from the _AvatarInfo.json.gz file in the root dir
		File[] info = IO.extractFiles(rootDir, "_AvatarInfo.json.gz");
		if (info == null || info.length !=1) throw new IOException("ERROR: failed to find the xxx_AvatarInfo.json.gz file in "+rootDir);
		String[] lines = IO.loadFile(info[0]);
		int gender = 0;
		for (String s: lines){
			if (s.contains("Gender")){
				if (s.contains("M")) gender = 1;
				else if (s.contains("F")) gender =2;
			}
		}
		if (gender == 0) throw new IOException("ERROR: failed to find the xxx_AvatarInfo.json.gz file in "+rootDir);
		Path bkg = null;
		if (gender == 1) bkg = tnRunner.getMaleBkg().toPath();
		else if (gender == 2 ) bkg = tnRunner.getFemaleBkg().toPath();
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"bkgPoN.hdf5").toPath(), bkg);
		
		//soft link in the new ones
		Path tumorBam = tumorExomeBamBedGvcf[0].toPath();
		Path tumorBai = fetchBamIndex(tumorExomeBamBedGvcf[0]).toPath();
		Path normalBam = normalExomeBamBedGvcf[0].toPath();
		Path normalBai = fetchBamIndex(normalExomeBamBedGvcf[0]).toPath();
		
		Path normalVcf = germlineVcf[0].toPath();
		Path normalTbi = germlineVcf[1].toPath();
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"tumor.bam").toPath(), tumorBam);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"tumor.bai").toPath(), tumorBai);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"normal.bam").toPath(), normalBam);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"normal.bai").toPath(), normalBai);
		
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"normal.vcf.gz").toPath(), normalVcf);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"normal.vcf.gz.tbi").toPath(), normalTbi);
	}
	
	private void removeCopyRatioLinks(File jobDir) throws IOException{
		File f = jobDir.getCanonicalFile();
		new File(f, "tumor.bam").delete();
		new File(f, "tumor.bai").delete();
		new File(f, "bkgPoN.hdf5").delete();
		new File(f, "normal.bam").delete();
		new File(f, "normal.bai").delete();
		new File(f, "normal.vcf.gz").delete();
		new File(f, "normal.vcf.gz.tbi").delete();
	}

	private void checkAlignments() throws IOException {
		info.add("Checking alignments...");
		//fastq available, align
		tumorExomeBamBedGvcf = exomeAlignQC(tumorExomeFastq, tnRunner.getMinReadCoverageTumor());
		normalExomeBamBedGvcf = exomeAlignQC(normalExomeFastq, tnRunner.getMinReadCoverageNormal());
		tumorTranscriptomeBam = transcriptomeAlignQC(tumorTransFastq);
	}

	/**For RNASeq Alignments and Picard CollectRNASeqMetrics*/
	private File transcriptomeAlignQC(FastqDataset fd) throws IOException {
		if (fd.getFastqs() == null) return null;
		info.add("\t"+fd.getName()+":");
		
		//make dir, ok if it already exists
		File alignDir = new File (rootDir, "Alignment/"+id+"_"+fd.getName()).getCanonicalFile();
		alignDir.mkdirs();
		
		//any files?
		HashMap<String, File> nameFile = IO.fetchNamesAndFiles(alignDir);
		if (nameFile.size() == 0) {
			launchTranscriptomeAlignQC(fd.getFastqs(), alignDir);
			return null;
		}
		
		//OK some files are present
		//COMPLETE
		else if (nameFile.containsKey("COMPLETE")){
			//find the final bam and bed
			File bamDir = new File(alignDir, "Bam");
			File[] finalBam = IO.extractFiles(bamDir, ".bam");
			
			if (finalBam == null || finalBam.length !=1) {
				info.add("\tThe alignemnt was marked COMPLETE but failed to find the bam file in "+bamDir);
				failed = true;
				return null;
			}
			
			//remove the linked fastq
			new File(alignDir, "1.fastq.gz").delete();
			new File(alignDir, "2.fastq.gz").delete();
			
			info.add("\t\tCOMPLETE "+alignDir);
			return finalBam[0];
		}
		
		//force a restart?
		else if (forceRestart  || restartFailedJobs && nameFile.containsKey("FAILED")){
			//cancel any slurm jobs and delete the directory
			cancelDeleteJobDir(nameFile, alignDir, info);
			//launch it
			launchTranscriptomeAlignQC(fd.getFastqs(), alignDir);
		}
		//QUEUED
		else if (nameFile.containsKey("QUEUED")){
			info.add("\t\tQUEUED "+alignDir);
			running = true;
		}
		//STARTED
		else if (nameFile.containsKey("STARTED")) {
			if (checkQueue(nameFile, alignDir, info) == false) failed = true;
			else running = true;
		}
		//FAILED but no forceRestart
		else if (nameFile.containsKey("FAILED")) {
			info.add("\t\tFAILED "+alignDir);
			failed = true;
		}
		//hmm no status files, probably something went wrong on the cluster? mark it as FAILED
		else {
			info.add("\t\tMarking as FAILED, no job status files in "+alignDir);
			new File(alignDir, "FAILED").createNewFile();
			failed = true;
		}
		return null;
	}
	
	private void launchTranscriptomeAlignQC(File[] fastq, File alignDir) throws IOException {
		info.add("\t\tLaunching "+alignDir);
		running = true;
		
		//want to replace the soft links
		createExomeAlignLinks(fastq, alignDir);
		
		//replace any launch scripts with the current
		File shellScript = copyInWorkflowDocs(tnRunner.getTranscriptomeAlignQCDocs(), alignDir);
		
		//clear any progress files
		removeProgressFiles(alignDir);
		
		//squeue the shell script
		new File(alignDir, "QUEUED").createNewFile();
		String alignDirPath = alignDir.getCanonicalPath();
		String[] output = IO.executeViaProcessBuilder(new String[]{"sbatch", "-J", alignDirPath.replace(tnRunner.getPathToTrim(), ""), "-D", alignDirPath, shellScript.getCanonicalPath()}, false);
		for (String o: output) info.add("\t\t"+o);
		
		deleteBamConcordance = true;
	}


	/**For Exomes, diff read coverage for passing bed generation
	 * @throws IOException */
	private File[] exomeAlignQC(FastqDataset fd, int minReadCoverage) throws IOException {
		if (fd.getFastqs() == null) return null;
		info.add("\t"+fd.getName()+":");
		
		//make dir, ok if it already exists
		File alignDir = new File (rootDir, "Alignment/"+id+"_"+fd.getName());
		alignDir.mkdirs();
		
		//any files?
		HashMap<String, File> nameFile = IO.fetchNamesAndFiles(alignDir);
		if (nameFile.size() == 0) {
			launchExomeAlignQC(fd.getFastqs(), alignDir, minReadCoverage);
			return null;
		}
		
		//OK some files are present
		//COMPLETE
		else if (nameFile.containsKey("COMPLETE")){
			//find the final bam and bed
			File[] finalBam = IO.extractFiles(new File(alignDir, "Bam"), "_final.bam");
			File[] passingBed = IO.extractFiles(new File(alignDir, "QC"), "_Pass.bed.gz");
			File[] gvcf = IO.extractFiles(new File(alignDir, "Vcfs"), "_Haplo.g.vcf.gz");
			File[] gvcfIndex = IO.extractFiles(new File(alignDir, "Vcfs"), "_Haplo.g.vcf.gz.tbi");
			
			//check for problems
			if (finalBam == null || finalBam.length !=1) {
				info.add("\tThe alignemnt was marked COMPLETE but failed to find the final bam file in the Bam/ in "+alignDir);
				failed = true;
				return null;
			}
			if (passingBed == null || passingBed.length !=1) {
				info.add("\tThe alignemnt was marked COMPLETE but failed to find the passing bed file in QC/ in "+alignDir);
				failed = true;
				return null;
			}
			if (gvcf == null || gvcf.length !=1) {
				info.add("\tThe alignemnt was marked COMPLETE but failed to find the gvcf file in Vcfs/ inside "+alignDir);
				failed = true;
				return null;
			}
			
			//remove the linked fastq and the sam2USeq.config.txt
			new File(alignDir, "1.fastq.gz").delete();
			new File(alignDir, "2.fastq.gz").delete();
			new File(alignDir, "sam2USeq.config.txt").delete();
			info.add("\t\tCOMPLETE "+alignDir);
			return new File[]{finalBam[0], passingBed[0], gvcf[0], gvcfIndex[0]};
		}
		
		//force a restart?
		else if (forceRestart  || restartFailedJobs && nameFile.containsKey("FAILED")){
			//cancel any slurm jobs and delete the directory
			cancelDeleteJobDir(nameFile, alignDir, info);
			//launch it
			launchExomeAlignQC(fd.getFastqs(), alignDir, minReadCoverage);
		}
		//QUEUED
		else if (nameFile.containsKey("QUEUED")){
			info.add("\t\tQUEUED "+alignDir);
			running = true;
		}
		//STARTED
		else if (nameFile.containsKey("STARTED")){
			if (checkQueue(nameFile, alignDir, info) == false) failed = true;
			else running = true;
		}
		//FAILED but no forceRestart
		else if (nameFile.containsKey("FAILED")){
			info.add("\t\tFAILED "+alignDir);
			failed = true;
		}
		//hmm no status files, probably something went wrong on the cluster? mark it as FAILED
		else {
			info.add("\t\tMarking as FAILED, no job status files in "+alignDir);
			new File(alignDir, "FAILED").createNewFile();
			failed = true;
		}
		return null;
	}
	
	private void launchVariantAnnotation(File jobDir, File vcfFile, String annotatedVcfParserOptions) throws IOException {
		info.add("\t\tLaunching "+jobDir);
		running = true;
		
		//want to create/ replace the soft links 
		//remove any linked fastq files
		File f = jobDir.getCanonicalFile();
		new File(f, vcfFile.getName()).delete();
		new File(f, vcfFile.getName()+".tbi").delete();

		//soft link in the new ones
		Path vcf = vcfFile.toPath();
		Path index = new File(vcfFile.getCanonicalPath()+".tbi").toPath();
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(), vcfFile.getName()).toPath(), vcf);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(), vcfFile.getName()+".tbi").toPath(), index);
		
		//write out config file
		IO.writeString(annotatedVcfParserOptions, new File(jobDir, "annotatedVcfParser.config.txt"));
		
		//replace any launch scripts with the current
		File shellScript = copyInWorkflowDocs(tnRunner.getVarAnnoDocs(), jobDir);

		//clear any progress files
		removeProgressFiles(jobDir);

		//squeue the shell script
		new File(jobDir, "QUEUED").createNewFile();
		String jobDirPath = jobDir.getCanonicalPath();
		String[] output = IO.executeViaProcessBuilder(new String[]{"sbatch", "-J", jobDirPath.replace(tnRunner.getPathToTrim(), ""), "-D", jobDirPath, shellScript.getCanonicalPath()}, false);
		for (String o: output) info.add("\t\t"+o);
	}

	
	private void launchSomaticVariantCalls(File jobDir) throws IOException {
		info.add("\t\tLaunching "+jobDir);
		running = true;
		
		//want to create/ replace the soft links 
		createSomaticVariantLinks(jobDir);
		
		//replace any launch scripts with the current
		File shellScript = copyInWorkflowDocs(tnRunner.getSomaticVarCallDocs(), jobDir);
		
		//clear any progress files
		removeProgressFiles(jobDir);
				
		//squeue the shell script
		new File(jobDir, "QUEUED").createNewFile();
		String jobDirPath = jobDir.getCanonicalPath();
		String[] output = IO.executeViaProcessBuilder(new String[]{"sbatch", "-J", jobDirPath.replace(tnRunner.getPathToTrim(), ""), "-D", jobDirPath, shellScript.getCanonicalPath()}, false);
		for (String o: output) info.add("\t\t"+o);
	}

	private void launchExomeAlignQC(File[] fastq, File alignDir, int minReadCoverage) throws IOException {
		info.add("\t\tLaunching "+alignDir);
		running = true;
		
		//want to replace the soft links
		createExomeAlignLinks(fastq, alignDir);
		
		//replace any launch scripts with the current
		File shellScript = copyInWorkflowDocs(tnRunner.getExomeAlignQCDocs(), alignDir);
		
		//clear any progress files
		removeProgressFiles(alignDir);
		
		//(over)write the minReadCoverage
		IO.writeString("-c "+ minReadCoverage, new File(alignDir, "sam2USeq.config.txt"));
		
		//squeue the shell script
		new File(alignDir, "QUEUED").createNewFile();
		String alignDirPath = alignDir.getCanonicalPath();
		String[] output = IO.executeViaProcessBuilder(new String[]{"sbatch", "-J", alignDirPath.replace(tnRunner.getPathToTrim(), ""), "-D", alignDirPath, shellScript.getCanonicalPath()}, false);
		for (String o: output) info.add("\t\t"+o);
		
		//delete BamConcordance if it exists
		deleteBamConcordance = true;
	}
	
	private void createExomeAlignLinks(File[] fastq, File alignDir) throws IOException {
		//remove any linked fastq files
		File f = alignDir.getCanonicalFile();
		new File(f, "1.fastq.gz").delete();
		new File(f,"2.fastq.gz").delete();

		//soft link in the new ones
		Path real1 = fastq[0].toPath();
		Path real2 = fastq[1].toPath();
		Files.createSymbolicLink(new File(alignDir.getCanonicalFile(),"1.fastq.gz").toPath(), real1);
		Files.createSymbolicLink(new File(alignDir.getCanonicalFile(),"2.fastq.gz").toPath(), real2);
	}
	
	private void createSomaticVariantLinks(File jobDir) throws IOException {
		//remove any linked bam and bed files
		removeSomaticLinks(jobDir);
		
		//soft link in the new ones
		Path tumorBam = tumorExomeBamBedGvcf[0].toPath();
		Path tumorBai = fetchBamIndex(tumorExomeBamBedGvcf[0]).toPath();
		Path tumorBed = tumorExomeBamBedGvcf[1].toPath();
		Path normalBam = normalExomeBamBedGvcf[0].toPath();
		Path normalBai = fetchBamIndex(normalExomeBamBedGvcf[0]).toPath();
		Path normalBed = normalExomeBamBedGvcf[1].toPath();
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"tumor.bam").toPath(), tumorBam);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"tumor.bai").toPath(), tumorBai);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"tumor.bed.gz").toPath(), tumorBed);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"normal.bam").toPath(), normalBam);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"normal.bai").toPath(), normalBai);
		Files.createSymbolicLink(new File(jobDir.getCanonicalFile(),"normal.bed.gz").toPath(), normalBed);
	}
	
	private void removeSomaticLinks(File jobDir) throws IOException{
		File f = jobDir.getCanonicalFile();
		new File(f, "tumor.bam").delete();
		new File(f, "tumor.bai").delete();
		new File(f, "tumor.bed.gz").delete();
		new File(f, "normal.bam").delete();
		new File(f, "normal.bai").delete();
		new File(f, "normal.bed.gz").delete();
	}
	
	public static boolean checkQueue(HashMap<String, File> nameFile, File jobDir, ArrayList<String> info) throws IOException{
		//find slurm script(s), pull the ID, and check it is still in the queue
		ArrayList<File> jobs = new ArrayList<File>();
		for (String s: nameFile.keySet()) {
			if (s.startsWith("slurm")) {
				jobs.add(nameFile.get(s));
			}
		}
		if (jobs.size() == 0) {
			info.add("\t\tThe job was marked as STARTED but couldn't find the slurm-xxx.out file in "+jobDir);
			return false;
		}
		String slurm = null;
		if (jobs.size() == 1) slurm = jobs.get(0).getName();
		else {
			//multiple jobs take last
			File[] toSort = new File[jobs.size()];
			jobs.toArray(toSort);
			Arrays.sort(toSort);
			slurm = toSort[toSort.length-1].getName();
		}
		//pull the job id
		Matcher mat = slurmJobId.matcher(slurm);
		if (mat.matches() == false) throw new IOException("\tFailed to parse the job id from  "+slurm);
		String jobId = mat.group(1);
		//check the queue
		info.add("\t\tChecking the slurm queue for "+jobId);
		String[] res = IO.executeViaProcessBuilder(new String[]{"squeue", "-j", jobId}, false);
		if (res.length < 2) {
			new File(jobDir,"STARTED").delete();
			new File(jobDir,"FAILED").createNewFile();
			info.add("The job was marked as STARTED but failed to find the "+slurm+" job in the queue for "+jobDir+", marking FAILED.");
			return false;
		}
		info.add("\t\tSTARTED and in queue, "+jobDir+"\n\t\t\t"+res[1]);
		return true;
	}

	public static void cancelDeleteJobDir(HashMap<String, File> nameFile, File jobDir, ArrayList<String> info){
		//send a scancel
		ArrayList<String> sb = new ArrayList<String>();
		for (String s: nameFile.keySet()) {
			if (s.startsWith("slurm")) {
				Matcher mat = slurmJobId.matcher(s);
				if (mat.matches()) sb.add(mat.group(1));
			}
		}
		if (sb.size() !=0){
			sb.add(0, "scancel");
			info.add("\t\tCanceling slurm jobs"+sb+ " from "+jobDir);
			IO.executeViaProcessBuilder(Misc.stringArrayListToStringArray(sb), false);
		}
		//delete dir
		info.add("\t\tDeleting "+jobDir);
		IO.deleteDirectory(jobDir);
		if (jobDir.exists()) IO.deleteDirectoryViaCmdLine(jobDir);
		jobDir.mkdirs();
	}
	
	public static File copyInWorkflowDocs(File[] workflowDocs, File jobDir) throws IOException {
		File shellScript = null;
		for (File f: workflowDocs) {
			File copy = new File(jobDir, f.getName());
			IO.copyViaFileChannel(f, copy);
			if (copy.getName().endsWith(".sh")) shellScript = f;
		}
		if (shellScript == null) throw new IOException("Failed to find the workflow xxx.sh file in "+workflowDocs[0].getParent());
		return shellScript;
	}

	/**Attempts to find a xxx.bai file in the same dir as the xxx.bam file.*/
	public static final Pattern bamName = Pattern.compile("(.+)\\.bam");
	public static File fetchBamIndex(File bam) throws IOException{
		String path = bam.getCanonicalPath();
		Matcher mat = bamName.matcher(path);
		File index = null;
		if (mat.matches()) index = new File(mat.group(1)+".bai");
		if (index == null || index.exists() == false) throw new IOException("Failed to find the xxx.bai index file for "+bam);
		return index;
	}

	public static void removeProgressFiles(File alignDir) {
		new File(alignDir, "FAILED").delete();
		new File(alignDir, "COMPLETE").delete();
		new File(alignDir, "STARTED").delete();
		new File(alignDir, "QUEUED").delete();
	}

	private boolean checkFastq() throws IOException {
		info.add("Checking Fastq availability...");
		File fastqDir = makeCheckFile(rootDir, "Fastq");
		tumorExomeFastq = new FastqDataset(fastqDir, "TumorExome", info);
		normalExomeFastq = new FastqDataset(fastqDir, "NormalExome", info);
		tumorTransFastq = new FastqDataset(fastqDir, "TumorTranscriptome", info);
		if (tumorExomeFastq.isFastqDirExists()) info.add("\tTumorExome\t"+ (tumorExomeFastq.getFastqs() != null));
		if (normalExomeFastq.isFastqDirExists()) info.add("\tNormalExome\t"+ (normalExomeFastq.getFastqs() != null));
		if (tumorTransFastq.isFastqDirExists()) info.add("\tTumorTranscriptome\t"+ (tumorTransFastq.getFastqs() != null));
		if (tumorExomeFastq.getFastqs() != null || normalExomeFastq.getFastqs() != null || tumorTransFastq.getFastqs() != null) return true;
		return false;
	}

	public static File makeCheckFile(File parentDir, String fileName) throws IOException {
		File f = new File(parentDir, fileName);
		if (f.exists() == false) return null;
		return f;
	}

	public FastqDataset getTumorExomeFastq() {
		return tumorExomeFastq;
	}

	public FastqDataset getNormalExomeFastq() {
		return normalExomeFastq;
	}

	public File getTumorTranscriptomeBamBed() {
		return tumorTranscriptomeBam;
	}

	public File[] getNormalExomeBamBedGvcf() {
		return normalExomeBamBedGvcf;
	}

	public File getRootDir() {
		return rootDir;
	}

	public String getId() {
		return id;
	}

	public boolean isFailed() {
		return failed;
	}

	public boolean isRunning() {
		return running;
	}
}
