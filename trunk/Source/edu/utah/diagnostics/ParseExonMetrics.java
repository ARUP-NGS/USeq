package edu.utah.diagnostics;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import util.gen.IO;
import util.gen.Misc;

public class ParseExonMetrics {
	private String rPath = "R";
	private String lPath = "pdflatx";
	private File outputFile = null;
	private File alignmentFile = null;
	private File duplicateFile = null;
	private File coverageFile = null;
	private File countFile = null;
	private File errorFile = null;
	private File mergedFile = null;
	
	//Alignments
	private Integer[] totalReads = {null,null,null};
	private Integer[] alignedReads = {null,null,null};
	private Float[] percentAligned = {null,null,null};
	private Float[] errorRate = {null,null,null};
	private Integer[] readsInPairs = {null,null,null};
	private Float[] percentInPairs = {null,null,null};
	private Float[] strandBalance = {null,null,null};
	
	//MergePairs
	private Integer incorrectPairs = null;
	private Float percentIncorrectPairs = null;
	private Integer singletons = null;
	private Float percentSingletons = null;
	private Float fractionOverlap = null;
	
	//Duplicates
	private Integer dupUnpaired = null;
	private Integer dupPaired = null;
	private Float percentDupUnpaired = null;
	private Float percentDupPaired = null;
	private Float percentDuplication = null;
	
	//Counts
	private Integer alignedCounts = null;
	private Integer allCounts = null;
	private Integer phiXCounts = null;
	private Integer adapterCounts = null;
	private Integer normalCounts = null;
	private Integer extraCounts = null;
	private Float perAligned = null;
	private Float perPhiX = null;
	private Float perAdapter = null;
	private Float perNormal = null;
	private Float perExtra = null;
	
	//Error raate
	private Float meanErrorRate = null;
	
	//Various files
	private File insertGraph = null;
	private File errorGraph = null;
	private File coverageGraph = null;
	private File coverageGraph2 = null;

	public ParseExonMetrics(String[] args) {
		this.parseArgs(args);
	}

	public static void main(String[] args) {
		ParseExonMetrics pm = new ParseExonMetrics(args);
		pm.run();

	}
	
	private void run() {
		System.out.println("Parsing Count Data...");
		this.parseCounts();
		
		System.out.println("Parsing USeq's CalculatePerCycleErrorRate...");
		this.parseErrorRate();
		
		System.out.println("Parsing Picard's Collect Alignment Metrics...");
		this.parseAlignmentMetrics();
	
		System.out.println("Parsing USeq's MergePairedAlignments...");
		//ProcessBuilder mergedProcess = new ProcessBuilder("java","-Xmx4g","-jar","/home/BioApps/USeq/Apps/MergePairedAlignments","-f",inputFile.toString());
		this.parseMerged();
		
		System.out.println("Parsing Picard's Mark Duplicate Metrics...");
		this.parseMarkDuplicates();
		
		System.out.println("Parsing USeq's Sam2USeq Coverage Rate...");
		this.parseCoverage();
		
		//Generate Latexfile
		File latexFile = new File(outputFile + ".tex");
		this.generateLatex(latexFile);
		ProcessBuilder latexProcess = new ProcessBuilder("pdflatex","--halt-on-error",latexFile.toString()); 
		this.runSystemCommand(latexProcess,"pdfLatex");
		this.runSystemCommand(latexProcess,"pdfLatex");
		
		this.deleteFiles();
		
	}
	
	private void parseCoverage() {
		try {
			BufferedReader br = new BufferedReader(new FileReader(coverageFile));
			
			Pattern histStart = Pattern.compile("BaseCoverage.+");
			Pattern histEnd = Pattern.compile("Total interrogated bases.+");
			
			ArrayList<Integer> coverage = new ArrayList<Integer>();
			ArrayList<Float> fraction = new ArrayList<Float>();
			ArrayList<Float> fractionOrGreater = new ArrayList<Float>();
			
			boolean found = false;
			String temp = null;
			
			while((temp = br.readLine()) != null) {
				Matcher mStart = histStart.matcher(temp);
				Matcher mEnd = histEnd.matcher(temp);
				
				if (mStart.matches()) {
					found = true;
				} else if (mEnd.matches()) {
					break;
				} else if (found) {
					if (temp.trim().equals("")) {
						continue;
					}
					String[] items = temp.split("\t");
					coverage.add(Integer.parseInt(items[0]));
					fraction.add(Float.parseFloat(items[2]));
					fractionOrGreater.add(Float.parseFloat(items[3]));
				}
			}
			
			this.generateRBarplot(coverage, fraction, "PEM.cov.txt", "Coverage across CCDS Bases", "Depth of Coverage", "Fraction At Given Coverage", coverageGraph);
			this.generateRBarplot(coverage, fractionOrGreater, "PEM.cov2.txt", "Coverage across CCDS Bases", "Depth of Coverage", "Fraction At Given Coverage or Greater", coverageGraph2);
			
			br.close();
			
		} catch (IOException ioex) {
			System.out.println("Could not read coverage file, exiting: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		}
	}
	
	private void parseErrorRate() {
		try {
			BufferedReader br = new BufferedReader(new FileReader(errorFile));
			
			Pattern histStart = Pattern.compile("Cycle#.+");
			ArrayList<Integer> xaxis = new ArrayList<Integer>();
			ArrayList<Float> values = new ArrayList<Float>();
			
			String temp = null;
			while ((temp=br.readLine()) != null) {
				Matcher m = histStart.matcher(temp);
				if (m.matches()) {
					break;
				}
			}
			
			while (!(temp=br.readLine()).equals("")) {
				String[] items = temp.split("\t");
				xaxis.add(Integer.parseInt(items[0]));
				values.add(Float.parseFloat(items[1]));
			}
			
			this.meanErrorRate = Float.parseFloat(br.readLine().split("\t")[1]);
			
			this.generateRBarplot(xaxis, values, ".PEM.error.txt", "Per Base Error Rate", "Position", "Error Rate", errorGraph);
			
			
			br.close();
		} catch (IOException ioex) {
			System.out.println("Could not process error rate file: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		}
	}
	
	private void parseMarkDuplicates() {
		try {
			BufferedReader br = new BufferedReader(new FileReader(duplicateFile));
			
			for (int i=0;i<7;i++) {
				br.readLine();
			}
			
			String[] stats = br.readLine().split("\t");
			
			Integer totalUnpaired = null;
			Integer totalPaired = null;
			
			totalUnpaired = Integer.parseInt(stats[1]);
			totalPaired = Integer.parseInt(stats[2]);
			dupUnpaired = Integer.parseInt(stats[4]);
			dupPaired = Integer.parseInt(stats[5]);
			percentDuplication = Float.parseFloat(stats[7]) * 100;
			percentDupPaired = dupPaired / (float)totalPaired * 100;
			percentDupUnpaired = dupUnpaired / (float)totalUnpaired * 100;
			
			br.close();
			
		} catch (IOException ioex) {
			System.out.println("Could not process duplicate file: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		}
	}
	
	private void parseAlignmentMetrics() {
		try {
			BufferedReader br = new BufferedReader(new FileReader(alignmentFile));
			
			for (int i=0;i<7;i++) {
				br.readLine();
			}
			
			for (int i=0;i<3;i++) {
				String[] read = br.readLine().split("\t");
				
				this.totalReads[i] = Integer.parseInt(read[1]);
				this.alignedReads[i] = Integer.parseInt(read[5]);
				this.percentAligned[i] = Float.parseFloat(read[6]) * 100;
				this.errorRate[i] = Float.parseFloat(read[13]);
				this.readsInPairs[i] = Integer.parseInt(read[16]);
				this.percentInPairs[i] = Float.parseFloat(read[17]) * 100;
				this.strandBalance[i] = Float.parseFloat(read[19]) * 100;
			}
			
			br.close();
			
		} catch (IOException ioex) {
			System.out.println("Error processing file: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		}
		
	}
	
	private void parseCounts() {
		BufferedReader br = null;
		
		try {
			br = new BufferedReader(new FileReader(countFile));
			this.allCounts = Integer.parseInt(br.readLine());
			this.alignedCounts = Integer.parseInt(br.readLine());
			this.normalCounts = Integer.parseInt(br.readLine());
			this.phiXCounts = Integer.parseInt(br.readLine());
			this.adapterCounts = Integer.parseInt(br.readLine());
			
			this.perPhiX = this.phiXCounts / (float) this.allCounts * 100;
			this.perAdapter = this.adapterCounts / (float) this.allCounts * 100;
			this.perAligned = this.alignedCounts / (float) this.allCounts * 100;
			this.extraCounts = this.alignedCounts - this.normalCounts - this.adapterCounts - this.phiXCounts;
			this.perNormal = this.normalCounts / (float) this.allCounts * 100;
			this.perExtra = this.extraCounts / (float) this.allCounts * 100;
									
			br.close();

		} catch (IOException ioex) {
			System.out.println("Could not read counts file: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		}
	}
	
	private void parseMerged() {
		try {
			BufferedReader br = new BufferedReader(new FileReader(mergedFile));
			Pattern p1 = Pattern.compile("\\s+(\\d+)\\s+# Non proper paired alignments");
			Pattern p2 = Pattern.compile("\\s+(\\d+)\\s+# Alignments missing mate paired alignment");
			Pattern p3 = Pattern.compile("\\s+(.+?)\\s+Fraction overlapping bases.+");
			Pattern p4 = Pattern.compile("\\s*Mapped genomic insert length.+");
			Pattern p5 = Pattern.compile("\\s+(\\d+)\\s+Total\\s+# alignments from sam/bam file");
			
			Integer totalReads = null;
			ArrayList<Integer> xaxis = new ArrayList<Integer>();
			ArrayList<Float> values = new ArrayList<Float>();
			
			String temp = null;
			while((temp = br.readLine()) != null) {
				Matcher m1 = p1.matcher(temp);
				Matcher m2 = p2.matcher(temp);
				Matcher m3 = p3.matcher(temp);
				Matcher m4 = p4.matcher(temp);
				Matcher m5 = p5.matcher(temp);
				
				if (m1.matches()) {
					incorrectPairs = Integer.parseInt(m1.group(1));
					percentIncorrectPairs = incorrectPairs / (float)totalReads * 100; 
				} else if (m2.matches()) {
					singletons = Integer.parseInt(m2.group(1));
					percentSingletons = singletons / (float)totalReads * 100;
				} else if (m3.matches()) {
					fractionOverlap = Float.parseFloat(m3.group(1));
				} else if (m4.matches()) {
					break;
				} else if (m5.matches()) {
					totalReads = Integer.parseInt(m5.group(1));
				} 
			}
			
			br.readLine(); //slurp space
			
			while(!(temp = br.readLine()).equals("")) {
				String[] items = temp.split("\t");
				xaxis.add(Integer.parseInt(items[0]));
				values.add(Float.parseFloat(items[1]));
			}
			
			this.generateRBarplot(xaxis, values, "PEM.insert.txt", "Insert Size Distribution", "Insert Size", "Counts", insertGraph);
			br.close();
			
		} catch (IOException ioex) {
			System.out.println("Error processing file: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		} catch (Exception ex) {
			System.out.println("Error :" + ex.getMessage());
			ex.printStackTrace();
			System.exit(1);
		}
	}
	
	private void generateRBarplot(ArrayList<Integer> xvalues, ArrayList<Float> yvalues,String suffix, String title, String xaxis, String yaxis, File output) {
		
		BufferedWriter bw = null;
		
		try {
			//Write data to file
			File insertData = new File(outputFile + suffix);
			bw = new BufferedWriter(new FileWriter(insertData));
			
			for (int i=0; i<xvalues.size(); i++) {
				bw.write(String.format("%d\t%f\n",xvalues.get(i),yvalues.get(i)));
			}
			
			bw.close();
			
			//Write rscript to file
			File scriptFile = new File(outputFile + ".RScript");
			File rOut = new File(outputFile + ".Rout");
			
			StringBuffer sb = new StringBuffer("");
			
			sb.append("library(ggplot2)\n");
			sb.append("data=read.table('" + insertData.getCanonicalPath() + "')\n");
			sb.append("p=ggplot(data,aes(x=V1,y=V2)) + geom_bar(stat='identity') + ");
			sb.append("scale_x_discrete('" + xaxis + "',breaks=round(seq(min(data$V1), max(data$V1), by=50),1)) + ");
			sb.append("scale_y_continuous('" + yaxis + "') +");
			sb.append("ggtitle(paste('" + outputFile.getName() + "','" + title + "',sep=': '))\n");
			sb.append("ggsave(p,file='" + output.getCanonicalPath() + "',width=6,height=3)\n");
	
			//write script to file
			IO.writeString(sb.toString(), scriptFile);
	
			//make command
			ProcessBuilder pb = new ProcessBuilder(rPath,"CMD","BATCH","--no-save","--no-restore",scriptFile.getCanonicalPath(),rOut.getCanonicalPath());
			
			Process p = pb.start();
			
			try {
				int retVal = p.waitFor();
				if (retVal != 0) {
					System.out.println("Creation of insertion length histogram failed, exiting (Look at Rout for errors)");
					System.exit(1);
				} else {
					scriptFile.delete();
					rOut.delete();
					insertData.delete();
				}
			} catch (Exception ex ) {
				System.out.println("Error running R command, exiting: " + ex.getMessage());
				System.exit(1);
			}
			
			
		} catch (IOException ioex) {
			System.out.println("Error writing insert size data to file: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		}
	}
	
	private void generateLatex(File latexFile) {
		
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(latexFile));
			
			String fixedName = outputFile.getName().replace("_", "\\_");
			//System.out.println(fixedName + " Why are you not working?");
			
			//Write document header
			bw.write("\\documentclass[11pt]{article}\n");
			bw.write("\\usepackage{verbatim}\n");
			bw.write("\\usepackage{hyperref}\n");
			bw.write("\\usepackage[all]{hypcap}\n");
			bw.write("\\usepackage{graphicx}\n");
			bw.write("\\usepackage{float}\n");
			bw.write("\\usepackage[font=sf,labelfont={sf,bf}, margin=1cm]{caption}\n");
			bw.write("\\usepackage{chngpage}\n\n");
			
			bw.write("\\begin{document}\n");
			bw.write("\\title{Sample Metrics: " + fixedName + "}\n");
			bw.write("\\author{USeq ParseExonMetrics}\n");
			Date d = new Date();
			bw.write("\\date{" + d + "}\n");
			bw.write("\\maketitle");
			
			//Write Count Data
			bw.write("\\section{Counts}\n");
			bw.write("\\paragraph{}\n");
			bw.write("Reads are aligned to the standard chromosome set, random and haplotype chromosomes, phiX and Illumina adapters.  The raw counts "
					+ "for each of these chromosome sets are listed in \\textbf{Table~\\ref{tab:1}}. \\emph{Data generated by USeq's CountChromosomes}.\n");
			
			bw.write("\\begin{table}[h]\n");
			bw.write("\\caption{Read Origin\\label{tab:1}}\n");
			bw.write("\\centering\n");
			bw.write("\\begin{tabular}{ l l }\n");
			bw.write("\\hline\n");
			bw.write("\\hline\n");
			bw.write("Total Reads & " + String.format("%,d", this.allCounts) + "\\\\\n");
			bw.write("Aligned Reads & " + String.format("%,d", this.alignedCounts) + " ( " + String.format("%,.2f\\%%",this.perAligned) + " )\\\\\n");
			bw.write("Standard hg19 Reads & " + String.format("%,d", this.normalCounts) + " ( " + String.format("%,.2f\\%%",this.perNormal) + " )\\\\\n");
			bw.write("Non-Standard hg19 Reads & " + String.format("%,d", this.extraCounts) + " ( " + String.format("%,.2f\\%%",this.perExtra) + " )\\\\\n");
			bw.write("PhiX Reads & " + String.format("%,d", this.phiXCounts) + " ( " + String.format("%,.2f\\%%",this.perPhiX) + " )\\\\\n");
			bw.write("Adapter Reads & " + String.format("%,d", this.adapterCounts) + " ( " + String.format("%,.2f\\%%",this.perAdapter) + " )\\\\\n");
			bw.write("\\hline\n");
			bw.write("\\end{tabular}\n");
			bw.write("\\end{table}\n");
			
			
			//Write Error Rate
			bw.write("\\section{Error Rate}\n");
			bw.write("\\paragraph{}\n");
			bw.write("The phiX library spiked in each Illumina sequencing lane can be used to calculate error rates. The mean error rate across the length of "
					+ "the read is \\verb|" + String.format("%.5f",this.meanErrorRate) + "|. Per base error rates can be found in \\textbf{Figure~\\ref{fig:1}}. "
					+ "\\emph{Data generated by USeq's CalculatePerCycleErrorRate.}\n");
			
			//Insert histogram
			bw.write("\\begin{figure}[H]");
			bw.write("\\centerline{\\includegraphics{"+errorGraph.getCanonicalPath()+"}}\n");
			bw.write("\\caption{Average per base error rates for phiX reads\\label{fig:1}}\n");
			bw.write("\\end{figure}\n\n");
				
			//Write Duplicates
			bw.write("\\section{Duplicates}\n");
			bw.write("\\paragraph{}\n");
			bw.write("Duplicate reads (same start point and orientation) are not expected in typical exome sequencing projects.  They are indicative "
					+ "of PCR amplication bias and could affect variation calling. Duplicate statistics can be found in \\textbf{Table~\\ref{tab:3}}.  \\emph{Generated using Picard's MarkDuplicates.}");
			
			bw.write("\\begin{table}[H]\n");
			bw.write("\\caption{Duplicate Reads\\label{tab:3}}\n");
			bw.write("\\centering\n");
			bw.write("\\begin{tabular}{ l l }\n");
			bw.write("\\hline\n");
			bw.write("\\hline\n");
			bw.write("Duplicate Unpaired Reads & " + String.format("%,d",this.dupUnpaired) + " ( " + String.format("%,.2f\\%%",this.percentDupUnpaired) + " )\\\\\n");
			bw.write("Duplicate Paired Reads & " + String.format("%,d",this.dupPaired) + " ( " + String.format("%,.2f\\%%",this.percentDupPaired) + " )\\\\\n");
			bw.write("Percent Duplications & " + String.format("%,.4f\\%%",this.percentDuplication) + "\\\\\n");
			bw.write("\\hline\n");
			bw.write("\\end{tabular}\n");
			bw.write("\\end{table}\n\n");
			
			//Write Alignment Metrics
			bw.write("\\section{Alignment}\n");
			bw.write("\\paragraph{}\n");
			bw.write("Alignment Statistics for standard chromosomes after recalibration, realignment and deduplication can be found in \\textbf{Table~\\ref{tab:2}}. \\emph{Data generated by Picard's "
					+ "CollectAlignmentStatistics and USeq's MergePairedSamAlignments}\n");
			
			bw.write("\\begin{table}[H]\n");
			bw.write("\\begin{adjustwidth}{-1in}{-1in}\n");
			bw.write("\\caption{Alignment Statistics\\label{tab:2}}\n");
			bw.write("\\centering\n");
			bw.write("\\begin{tabular}{ l l l l }\n");
			bw.write("\\hline\n");
			bw.write("\\hline\n");
			bw.write("Metric & Read1 & Read2 & Combined \\\\\n");
			bw.write("\\hline\n");
			bw.write("Total Reads & " + String.format("%,d", this.totalReads[0]) + " & " + String.format("%,d", this.totalReads[1]) + 
					" & " + String.format("%,d", this.totalReads[2]) + "\\\\\n");
			bw.write("Aligned Reads & " + String.format("%,d", this.alignedReads[0]) + " (" + String.format("%,.2f\\%%",this.percentAligned[0]) + ") & "
					+ String.format("%,d", this.alignedReads[1]) + " (" + String.format("%,.2f\\%%",this.percentAligned[1]) + ") & "
					+ String.format("%,d", this.alignedReads[2]) + " (" + String.format("%,.2f\\%%",this.percentAligned[2]) + ")\\\\\n");
			bw.write("Paired & " + String.format("%,d",this.readsInPairs[0]) + " (" + String.format("%,.2f\\%%",this.percentInPairs[0]) + ") & "
					+ String.format("%,d",this.readsInPairs[1]) + " (" + String.format("%,.2f\\%%",this.percentInPairs[1]) + ") & "
					+ String.format("%,d",this.readsInPairs[2]) + " (" + String.format("%,.2f\\%%",this.percentInPairs[2]) + ")\\\\\n");
			bw.write("Strand Balance & " + String.format("%,.2f\\%%",this.strandBalance[0]) + " & " +
					String.format("%,.2f\\%%",this.strandBalance[1]) + " & " + String.format("%,.2f\\%%",this.strandBalance[2]) + "\\\\\n");
			bw.write("Error Rate & " + String.format("%,.5f",this.errorRate[0]) + " & " + String.format("%,.5f",this.errorRate[1]) + " & " + 
					String.format("%,.5f",this.errorRate[2]) + "\\\\\n");
			bw.write("Incorrectly Paired & & & " + String.format("%,d",this.incorrectPairs) + " (" + String.format("%,.2f\\%%",this.percentIncorrectPairs) + ")\\\\\n");
			bw.write("Singletons & & & " + String.format("%,d",this.singletons) + " (" + String.format("%,.2f\\%%",this.percentSingletons) + ")\\\\\n");
			
			bw.write("\\hline\n");
			bw.write("\\end{tabular}\n");
			bw.write("\\end{adjustwidth}\n");
			bw.write("\\end{table}\n\n");
			
			//Overlapping reads section
			bw.write("\\section{Overlapping Reads}\n");
			bw.write("\\paragraph{}\n");
			bw.write("Depending on the insert size, paired reads can overlap with each other.  Some software, like GATK, is aware of the problem and corrects "
					+ "for it, but not all.  If the median insert size is small and the amount of overlap is high, it might be worth merging "
					+ "the reads using USeq's MergePairedSamAlignments.\n");
			bw.write("\\paragraph{}\n");
			bw.write("The fraction of overlapping bases is \\verb|" + String.format("%.5f",this.fractionOverlap) + "|. A histogram of sample insert sizes can "
					+ "be found in \\textbf{Figure~\\ref{fig:2}}. \\emph{Data generated by USeq's MergePairedSamAlignments.}\n");
			
			//Insert histogram
			bw.write("\\begin{figure}[H]");
			bw.write("\\centerline{\\includegraphics{"+insertGraph.getCanonicalPath()+"}}\n");
			bw.write("\\caption{Sample insert size distribution\\label{fig:2}}.\n");
			bw.write("\\end{figure}\n\n");
			
			//Write coverage
			bw.write("\\section{Coverage}\n");
			bw.write("\\paragraph{}\n");
			bw.write("Depth of coverage plots across CCDS exons can be found in \\textbf{Figure~\\ref{fig:3}} and \\textbf{Figure~\\ref{fig:4}}.  \\emph{Data generated by USeq's Sam2USeq.}\n");
			
			//Insert histogram
			bw.write("\\begin{figure}[H]");
			bw.write("\\centerline{\\includegraphics{"+coverageGraph.getCanonicalPath()+"}}\n");
			bw.write("\\caption{Fraction of bases at each level of coverage.\\label{fig:3}}\n");
			bw.write("\\end{figure}\n\n");
			
			//Insert histogram
			bw.write("\\begin{figure}[H]");
			bw.write("\\centerline{\\includegraphics{"+coverageGraph2.getCanonicalPath()+"}}\n");
			bw.write("\\caption{Fraction of bases at each level of coverage or greater.\\label{fig:4}}\n");
			bw.write("\\end{figure}\n\n");
			
			
			
			//Write document closer
			bw.write("\\end{document}\n");
			bw.flush();
			bw.close();
		} catch (IOException ioex) {
			System.out.println("Error writing to file, exiting: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		} 
		
		
	}
	
	private void deleteFiles() {
		ArrayList<String> toDelete = new ArrayList<String>();
		
		//toDelete.add(sampleName + ".phiX.counts.txt");
		//toDelete.add(sampleName + ".adapter.counts.txt");
		//toDelete.add(sampleName + ".normal.counts.txt");
		//toDelete.add(sampleName + ".all.counts.txt");
		//toDelete.add(sampleName + ".metrics.txt");
		//toDelete.add(sampleName + ".merged.txt");
		//toDelete.add(sampleName + ".error.txt");
		//toDelete.add(sampleName + ".duplicates.txt");
		//toDelete.add(sampleName + ".coverage.txt");
		//toDelete.add("snappy-1.0.3-libsnappyjava.so");
		//toDelete.add(sampleName + "query_MPSA*");
		
		
		toDelete.add(outputFile + ".out");
		toDelete.add(outputFile + ".tex");
		toDelete.add(outputFile + ".aux");
		toDelete.add(outputFile + ".log");

		
		
		File file = null;
		for (String f: toDelete) {
			file = new File(f);
			file.delete();
		}
	}
	
	
	private void runSystemCommand(ProcessBuilder pb, String commandName) {
		
		try {
			Process pa = pb.start();
			
			BufferedReader br = new BufferedReader(new InputStreamReader(pa.getErrorStream()));
			
			StringBuffer sb = new StringBuffer();
			String temp = null;
			while ((temp = br.readLine()) != null) {
				sb.append(temp + "\n");
			}
			
			int retVal = pa.waitFor();
			
			if (retVal != 0) {	
				System.out.println("Error running " + commandName + ", exiting: \n" + sb.toString());
			} else {
				System.out.println("Finished!");
			}
			
		} catch (IOException ioex) {
			System.out.println("Could not run command, exiting: " + ioex.getMessage());
			ioex.printStackTrace();
			System.exit(1);
		} catch (InterruptedException iex) {
			System.out.println(commandName + " was interrupted, exiting: " + iex.getMessage());
			iex.printStackTrace();
			System.exit(1);
		}
	}
	
	private void parseArgs(String[] args) {
		Pattern pat = Pattern.compile("-[a-z]");
		for (int i = 0; i<args.length; i++){
			String lcArg = args[i].toLowerCase();
			Matcher mat = pat.matcher(lcArg);
			if (mat.matches()){
				char test = args[i].charAt(1);
				try{
					switch (test){
					case 'a': alignmentFile = new File(args[++i]); break;
					case 'b': countFile = new File(args[++i]); break;
					case 'c': coverageFile = new File(args[++i]); break;
					case 'd': duplicateFile = new File(args[++i]); break;
					case 'e': errorFile = new File(args[++i]); break;
					case 'f': mergedFile = new File(args[++i]); break;
					case 'o': outputFile = new File(args[++i]); break;
					case 'r': rPath = args[++i]; break;
					case 'l': lPath = args[++i]; break;
					case 'h': usage(); System.exit(0);
					default: Misc.printExit("\nProblem, unknown option! " + mat.group());
					}
				}
				catch (Exception e){
					Misc.printExit("\nSorry, something doesn't look right with this parameter request: -"+test);
				}
			}
		}
		
		if (alignmentFile == null) {
			exitMessage("Alignment file not specified, exiting");
		}
		
		if (countFile == null) {
			exitMessage("Counts file not specified, exiting");
		}
		
		if (coverageFile == null) {
			exitMessage("Coverage file not specified, exiting");
		}
		
		if (duplicateFile == null) {
			exitMessage("Duplicate file not specified, exiting");
		}
		
		if (errorFile == null) {
			exitMessage("Error file not specified, exiting");
		}
		
		if (mergedFile == null) {
			exitMessage("Duplicate file not specified, exiting");
		}
		
		if (outputFile == null) {
			exitMessage("Output File not specified, exiting");
		} 
		
		
		this.insertGraph = new File(this.outputFile + "_insert.pdf");
		this.errorGraph = new File(this.outputFile + "_error.pdf");
		this.coverageGraph = new File(this.outputFile + "_coverage.pdf");
		this.coverageGraph2 = new File(this.outputFile + "_coverage2.pdf");
		
		this.insertGraph.deleteOnExit();
		this.errorGraph.deleteOnExit();
		this.coverageGraph.deleteOnExit();
		this.coverageGraph2.deleteOnExit();
		
	}
	
	private void exitMessage(String message) {
		usage();
		System.out.println(message);
		System.exit(1);
	}
	
	private void usage() {
		System.out.println("\n" +
				"**************************************************************************************\n" +
				"**                         Create Exon Summary Metrics : April 2013                 **\n" +
				"**************************************************************************************\n" +
				"This script runs a bunch of summary metric programs and compiles the results.  It uses\n" +
				"R and LaTex to generate a fancy pdf as an output. \n\n\n" +

				"Required:\n"+
				"-a Alignment statistics from Picard's CollectAlignmentMetrics\n"+
				"-b Alignment counts from USeq's CountChromosome\n"+
				"-c Coverage of CCDS exons from USeq's Sam2USeq\n" +
				"-d Duplication statics from Picard's MarkDuplicates\n"+
				"-e Error rate from USeq's CalculatePerCycleErrorRate\n" +
				"-f Overlap Statistics from USeq's MergePaired Sam Alignment\n" +
				"-o Output file name\n"+
				"Optional\n" +
				"-r Path to R\n" +
				"-l Path to pdflatex\n" +
				"\n\n"+

				"Example: java -Xmx1500M -jar pathTo/USeq/Apps/VCFAnnovar -v 9908R.vcf                 \n" +
		        "**************************************************************************************\n");
	}

}
