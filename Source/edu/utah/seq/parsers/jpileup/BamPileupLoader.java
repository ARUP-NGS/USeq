package edu.utah.seq.parsers.jpileup;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import edu.utah.seq.data.sam.SamAlignment;
import edu.utah.seq.data.sam.SamLayoutForMutation;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.samtools.reference.ReferenceSequence;
import util.bio.annotation.Bed;
import util.gen.IO;

public class BamPileupLoader implements Runnable { 

	//fields
	private boolean failed = false;
	private SamReader[] samReaders = null;
	private int minBaseQuality = 0;
	private int minMappingQuality = 0;
	private PrintWriter out = null;
	private IndexedFastaSequenceFile fasta = null;
	private Bed[] regions = null;
	private File resultsFile = null;
	private boolean includeOverlaps;
	private boolean printAll;
	private boolean verbose; 


	public BamPileupLoader (BamPileup bamPileup, int loaderIndex, Bed[] regions) throws IOException{
		minBaseQuality = bamPileup.getMinBaseQuality();
		minMappingQuality = bamPileup.getMinMappingQuality();
		this.regions = regions;
		includeOverlaps = bamPileup.isIncludeOverlaps();
		printAll = bamPileup.isPrintAll();
		verbose = bamPileup.isVerbose();

		//create gzipper for results
		resultsFile = new File(bamPileup.getTempDir(), loaderIndex+"_tempBamPileup.txt");
		resultsFile.deleteOnExit();
		out = new PrintWriter (new FileWriter(resultsFile));
		
		//create sam readers
		File[] bamFiles = bamPileup.getBamFiles();
		samReaders = new SamReader[bamFiles.length];
		for (int i=0; i< samReaders.length; i++) {
			samReaders[i] = bamPileup.getSamFactory().open(bamFiles[i]);
			if (samReaders[i].hasIndex() == false) {
				failed = true;
				throw new IOException("Failed to find an index for "+bamFiles[i]);
			}
		}

		//add header?
		if (loaderIndex == 0) {
			out.println("# MinMapQual\t"+minMappingQuality);
			out.println("# MinBaseQual\t"+minBaseQuality);
			out.println("# IncludeOverlappingBpCounts "+includeOverlaps);
			out.println("# PrintAll "+printAll);
			out.println("# Bed "+IO.getCanonicalPath(bamPileup.getBedFile()));
			for (int i=0; i<bamFiles.length; i++) out.println("# BamCram\t"+i+"\t"+IO.getCanonicalPath(bamFiles[i]));
			out.println("# Chr\t1BasePos\tRef\tA,C,G,T,N,Del,Ins,FailBQ");
		}


		//create fasta sequence reader
		fasta = new IndexedFastaSequenceFile(bamPileup.getFastaFile());
		if (fasta.isIndexed() == false) throw new IOException("\nError: cannot find your xxx.fai index or the multi fasta file isn't indexed\n");


	}

	public void run() {	
		try {
			//for each region
			int counter = 0;			
			for (Bed region: regions) {
				if (counter++ > 100) {
					counter = 0;
					if (verbose) IO.p(".");
				}
				String chr = region.getChromosome();

				//pileup each base from each bam
				BaseCount[][] bamBC = new BaseCount[samReaders.length][];
				for (int i=0; i< samReaders.length; i++ ) {
					bamBC[i]  = pileup(chr, region.getStart(), region.getStop(), samReaders[i]);
				}

				//for each base
				StringBuilder sb  = null;
				boolean noCounts = true;
				for (int bp=0; bp< region.getLength(); bp++) {
					sb  = new StringBuilder();
					noCounts = true;
					
					sb.append(chr); sb.append("\t");
					sb.append((bamBC[0][bp].bpPosition +1)); sb.append("\t");
					sb.append(bamBC[0][bp].ref);
					
					//out.print(chr+"\t"+ (bamBC[0][bp].bpPosition +1)+ "\t"+ bamBC[0][bp].ref);

					//for each bam
					for (int b = 0; b<samReaders.length; b++ ) {
						sb.append("\t");
						if (bamBC[b][bp].loadStringBuilderWithCounts(sb)  == true) noCounts = false;
					}
					if (noCounts == false || printAll == true) out.println(sb.toString());
				}
			}
		} catch (Exception e) {
			failed = true;
			System.err.println("\nError: problem processing\n" );
			e.printStackTrace();
		} finally {
			try {
				out.close();
				fasta.close();
				for (SamReader sr: samReaders) sr.close();
			} catch (IOException e) {}
		}
	}

	private BaseCount[] pileup(String chr, int start, int stop, SamReader samReader) throws Exception{
		//watch end
		int stopPlusOne = stop+1;
		int chromEnd = (int)fasta.getIndex().getIndexEntry(chr).getSize();
		if (stopPlusOne > chromEnd) stopPlusOne = chromEnd;
		
		//create container for counts
		ReferenceSequence p = fasta.getSubsequenceAt(chr, start+1, stopPlusOne);
		
		char[] refSeq = new String(p.getBases()).toCharArray();
		BaseCount[] bc = new BaseCount[stop-start];
		int counter = 0;
		for (int i=start; i< stop; i++) bc[counter] = new BaseCount(i, refSeq[counter++]);

		//fetch alignments
		SAMRecordIterator it = samReader.queryOverlapping(chr, start-1, stop+1);
		if (it.hasNext() == false) {
			it.close();
			return bc;
		}

		//for each record
		while (it.hasNext()){

			SAMRecord sam = it.next();
			if (sam.getMappingQuality() < minMappingQuality) continue;

			//make a layout
			SamAlignment sa = new SamAlignment(sam.getSAMString().trim(), true);
			SamLayoutForMutation layout = new SamLayoutForMutation(sa);
			String readName = sa.getName();

			//for each base in the region see if it overlaps
			counter = 0;
			for (int i = start; i< stop; i++){
				int index = layout.findBaseIndex(i);
				//System.out.println(i+ " i \t index "+index);

				//present in the alignment?
				if (index == -1) {
					//System.out.println("\tNotFound");
					counter++;
					continue;
				}

				//watch out for -1, not set
				int qual = layout.getQual()[index];
				if (qual == -1) {
					counter++;
					continue;
				}

				//mate already processed
				if (includeOverlaps == false) {
					if (bc[counter].readNames.contains(readName)) {
						counter++;
						continue;
					}
					else bc[counter].readNames.add(readName);
				}


				char call = layout.getCall()[index];

				if (call == 'M') {
					char base = layout.getSeq()[index];
					//System.out.println("\tMatch "+layout.getSeq()[index]);
					if (base == 'N' ) bc[counter].n++;
					else if (qual < minBaseQuality) bc[counter].failQual++;
					else bc[counter].increment(base);
				}
				//an deletion
				else if (call == 'D') {
					bc[counter].del++;
					//System.out.println("\tdeletion");
				}
				//an insertion
				else if (call == 'I') {
					bc[counter].ins++;

					//advance till base pos changes
					int[] pos = layout.getPos();
					char[] calls = layout.getCall();
					int currPos = pos[index];

					for (int x=index+1; x<pos.length; x++) {
						//diff base? exit
						if (pos[x] != currPos) break;
						//same base, score M or dels
						else {
							call = calls[x];
							if (call == 'M') {
								char base = layout.getSeq()[index];
								//System.out.println("\tMatch "+layout.getSeq()[index]);
								if (base == 'N' ) bc[counter].n++;
								else if (qual < minBaseQuality) bc[counter].failQual++;
								else bc[counter].increment(base);
							}
							//an deletion
							else if (call == 'D') {
								bc[counter].del++;
								//System.out.println("\tdeletion");
							}
							//do nothing
						}
					}
					//System.out.println("\tinsertion ");
				}

				//nope must be a masked base (S,H) or N so skip
				//else System.out.println("\tMask or N "+call);
				counter++;
			}
		}
		it.close();
		return bc;
	}

	public boolean isFailed() {
		return failed;
	}

	public File getResultsFile() {
		return resultsFile;
	}
}
