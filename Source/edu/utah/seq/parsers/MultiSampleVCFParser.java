package edu.utah.seq.parsers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Pattern;

import edu.utah.ames.bioinfo.Misc;

import util.gen.Gzipper;
import util.gen.IO;

/*
##fileformat=VCFv4.1
##ApplyRecalibration="analysis_type=ApplyRecalibration input_file=[] read_buffer_size=null phone_home=STANDARD gatk_key=null tag=NA read_filter=[] intervals=null excludeIntervals=null interval_set_rule=UNION interval_merging=ALL interval_padding=0 reference_sequence=hg19.fasta nonDeterministicRandomSeed=false disableRandomization=false maxRuntime=-1 maxRuntimeUnits=MINUTES downsampling_type=BY_SAMPLE downsample_to_fraction=null downsample_to_coverage=1000 baq=OFF baqGapOpenPenalty=40.0 fix_misencoded_quality_scores=false allow_potentially_misencoded_quality_scores=false performanceLog=null useOriginalQualities=false BQSR=null quantize_quals=0 disable_indel_quals=false emit_original_quals=false preserve_qscores_less_than=6 globalQScorePrior=-1.0 allow_bqsr_on_reduced_bams_despite_repeated_warnings=false defaultBaseQualities=-1 validation_strictness=SILENT remove_program_records=false keep_program_records=false unsafe=null num_threads=1 num_cpu_threads_per_data_thread=1 num_io_threads=0 monitorThreadEfficiency=false num_bam_file_handles=null read_group_black_list=null pedigree=[] pedigreeString=[] pedigreeValidationType=STRICT allow_intervals_with_unindexed_bam=false generateShadowBCF=false logging_level=INFO log_to_file=null help=false version=false input=[(RodBinding name=input source=rawSNP.vcf)] recal_file=(RodBinding name=recal_file source=snp.recal) tranches_file=snp.tranches out=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub no_cmdline_in_header=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub sites_only=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub bcf=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub ts_filter_level=99.0 ignore_filter=null mode=SNP filter_mismatching_base_and_quals=false"
##FILTER=<ID=LowQual,Description="Low quality">
##FILTER=<ID=VQSRTrancheSNP99.00to99.90,Description="Truth sensitivity tranche level for SNP model at VQS Lod: -7.0875 <= x < 1.6023">
##FILTER=<ID=VQSRTrancheSNP99.90to100.00+,Description="Truth sensitivity tranche level for SNP model at VQS Lod < -12535.2851">
##FILTER=<ID=VQSRTrancheSNP99.90to100.00,Description="Truth sensitivity tranche level for SNP model at VQS Lod: -12535.2851 <= x < -7.0875">
##FORMAT=<ID=AD,Number=.,Type=Integer,Description="Allelic depths for the ref and alt alleles in the order listed">
##FORMAT=<ID=DP,Number=1,Type=Integer,Description="Approximate read depth (reads with MQ=255 or with bad mates are filtered)">
##FORMAT=<ID=GQ,Number=1,Type=Integer,Description="Genotype Quality">
##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">
##FORMAT=<ID=PL,Number=G,Type=Integer,Description="Normalized, Phred-scaled likelihoods for genotypes as defined in the VCF specification">
##INFO=<ID=AC,Number=A,Type=Integer,Description="Allele count in genotypes, for each ALT allele, in the same order as listed">
##INFO=<ID=AF,Number=A,Type=Float,Description="Allele Frequency, for each ALT allele, in the same order as listed">
##INFO=<ID=AN,Number=1,Type=Integer,Description="Total number of alleles in called genotypes">
##INFO=<ID=BaseQRankSum,Number=1,Type=Float,Description="Z-score from Wilcoxon rank sum test of Alt Vs. Ref base qualities">
##INFO=<ID=DB,Number=0,Type=Flag,Description="dbSNP Membership">
##INFO=<ID=DP,Number=1,Type=Integer,Description="Approximate read depth; some reads may have been filtered">
##INFO=<ID=DS,Number=0,Type=Flag,Description="Were any of the samples downsampled?">
##INFO=<ID=Dels,Number=1,Type=Float,Description="Fraction of Reads Containing Spanning Deletions">
##INFO=<ID=END,Number=1,Type=Integer,Description="Stop position of the interval">
##INFO=<ID=FS,Number=1,Type=Float,Description="Phred-scaled p-value using Fisher's exact test to detect strand bias">
##INFO=<ID=HaplotypeScore,Number=1,Type=Float,Description="Consistency of the site with at most two segregating haplotypes">
##INFO=<ID=InbreedingCoeff,Number=1,Type=Float,Description="Inbreeding coefficient as estimated from the genotype likelihoods per-sample when compared against the Hardy-Weinberg expectation">
##INFO=<ID=MLEAC,Number=A,Type=Integer,Description="Maximum likelihood expectation (MLE) for the allele counts (not necessarily the same as the AC), for each ALT allele, in the same order as listed">
##INFO=<ID=MLEAF,Number=A,Type=Float,Description="Maximum likelihood expectation (MLE) for the allele frequency (not necessarily the same as the AF), for each ALT allele, in the same order as listed">
##INFO=<ID=MQ,Number=1,Type=Float,Description="RMS Mapping Quality">
##INFO=<ID=MQ0,Number=1,Type=Integer,Description="Total Mapping Quality Zero Reads">
##INFO=<ID=MQRankSum,Number=1,Type=Float,Description="Z-score From Wilcoxon rank sum test of Alt vs. Ref read mapping qualities">
##INFO=<ID=QD,Number=1,Type=Float,Description="Variant Confidence/Quality by Depth">
##INFO=<ID=RPA,Number=.,Type=Integer,Description="Number of times tandem repeat unit is repeated, for each allele (including reference)">
##INFO=<ID=RU,Number=1,Type=String,Description="Tandem repeat unit (bases)">
##INFO=<ID=ReadPosRankSum,Number=1,Type=Float,Description="Z-score from Wilcoxon rank sum test of Alt vs. Ref read position bias">
##INFO=<ID=STR,Number=0,Type=Flag,Description="Variant is a short tandem repeat">
##INFO=<ID=VQSLOD,Number=1,Type=Float,Description="Log odds ratio of being a true variant versus being false under the trained gaussian mixture model">
##INFO=<ID=culprit,Number=1,Type=String,Description="The annotation which was the worst performing in the Gaussian mixture model, likely the reason why the variant was filtered out">
##SelectVariants="analysis_type=SelectVariants input_file=[] read_buffer_size=null phone_home=STANDARD gatk_key=null tag=NA read_filter=[] intervals=null excludeIntervals=null interval_set_rule=UNION interval_merging=ALL interval_padding=0 reference_sequence=hg19.fasta nonDeterministicRandomSeed=false disableRandomization=false maxRuntime=-1 maxRuntimeUnits=MINUTES downsampling_type=BY_SAMPLE downsample_to_fraction=null downsample_to_coverage=1000 baq=OFF baqGapOpenPenalty=40.0 fix_misencoded_quality_scores=false allow_potentially_misencoded_quality_scores=false performanceLog=null useOriginalQualities=false BQSR=null quantize_quals=0 disable_indel_quals=false emit_original_quals=false preserve_qscores_less_than=6 globalQScorePrior=-1.0 allow_bqsr_on_reduced_bams_despite_repeated_warnings=false defaultBaseQualities=-1 validation_strictness=SILENT remove_program_records=false keep_program_records=false unsafe=null num_threads=1 num_cpu_threads_per_data_thread=1 num_io_threads=0 monitorThreadEfficiency=false num_bam_file_handles=null read_group_black_list=null pedigree=[] pedigreeString=[] pedigreeValidationType=STRICT allow_intervals_with_unindexed_bam=false generateShadowBCF=false logging_level=INFO log_to_file=null help=false version=false variant=(RodBinding name=variant source=snp.vcf) discordance=(RodBinding name= source=UNBOUND) concordance=(RodBinding name= source=UNBOUND) out=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub no_cmdline_in_header=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub sites_only=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub bcf=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub sample_name=[] sample_expressions=null sample_file=null exclude_sample_name=[] exclude_sample_file=[] select_expressions=[VQSLOD>4.0] excludeNonVariants=false excludeFiltered=false restrictAllelesTo=ALL keepOriginalAC=false mendelianViolation=false mendelianViolationQualThreshold=0.0 select_random_fraction=0.0 remove_fraction_genotypes=0.0 selectTypeToInclude=[] keepIDs=null fullyDecode=false forceGenotypesDecode=false justRead=false maxIndelSize=2147483647 ALLOW_NONOVERLAPPING_COMMAND_LINE_SAMPLES=false filter_mismatching_base_and_quals=false"
##UnifiedGenotyper="analysis_type=UnifiedGenotyper input_file=[carcinoid5.bam, carcinoid6.bam, carcinoid7.bam, colon1.bam, colon2.bam, colon3.bam, colon4.bam] read_buffer_size=null phone_home=STANDARD gatk_key=null tag=NA read_filter=[] intervals=null excludeIntervals=null interval_set_rule=UNION interval_merging=ALL interval_padding=0 reference_sequence=hg19.fasta nonDeterministicRandomSeed=false disableRandomization=false maxRuntime=-1 maxRuntimeUnits=MINUTES downsampling_type=BY_SAMPLE downsample_to_fraction=null downsample_to_coverage=250 baq=OFF baqGapOpenPenalty=40.0 fix_misencoded_quality_scores=false allow_potentially_misencoded_quality_scores=false performanceLog=null useOriginalQualities=false BQSR=null quantize_quals=0 disable_indel_quals=false emit_original_quals=false preserve_qscores_less_than=6 globalQScorePrior=-1.0 allow_bqsr_on_reduced_bams_despite_repeated_warnings=false defaultBaseQualities=-1 validation_strictness=SILENT remove_program_records=false keep_program_records=false unsafe=null num_threads=1 num_cpu_threads_per_data_thread=1 num_io_threads=0 monitorThreadEfficiency=false num_bam_file_handles=null read_group_black_list=null pedigree=[] pedigreeString=[] pedigreeValidationType=STRICT allow_intervals_with_unindexed_bam=false generateShadowBCF=false logging_level=INFO log_to_file=null help=false version=false genotype_likelihoods_model=SNP pcr_error_rate=1.0E-4 computeSLOD=false annotateNDA=false pair_hmm_implementation=ORIGINAL min_base_quality_score=17 max_deletion_fraction=0.05 min_indel_count_for_genotyping=5 min_indel_fraction_per_sample=0.25 indel_heterozygosity=1.25E-4 indelGapContinuationPenalty=10 indelGapOpenPenalty=45 indelHaplotypeSize=80 indelDebug=false ignoreSNPAlleles=false allReadsSP=false ignoreLaneInfo=false reference_sample_calls=(RodBinding name= source=UNBOUND) reference_sample_name=null sample_ploidy=2 min_quality_score=1 max_quality_score=40 site_quality_prior=20 min_power_threshold_for_calling=0.95 min_reference_depth=100 exclude_filtered_reference_sites=false heterozygosity=0.001 genotyping_mode=DISCOVERY output_mode=EMIT_VARIANTS_ONLY standard_min_confidence_threshold_for_calling=30.0 standard_min_confidence_threshold_for_emitting=30.0 alleles=(RodBinding name= source=UNBOUND) max_alternate_alleles=6 contamination_fraction_to_filter=0.05 contamination_fraction_per_sample_file=null p_nonref_model=EXACT_INDEPENDENT logRemovedReadsFromContaminationFiltering=null exactcallslog=null dbsnp=(RodBinding name=dbsnp source=hg19.dbsnp.vcf) comp=[] out=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub no_cmdline_in_header=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub sites_only=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub bcf=org.broadinstitute.sting.gatk.io.stubs.VariantContextWriterStub debug_file=null metrics_file=null annotation=[] excludeAnnotation=[] filter_mismatching_base_and_quals=false"
##contig=<ID=chr1,length=249250621,assembly=hg19>
##contig=<ID=chr2,length=243199373,assembly=hg19>
##contig=<ID=chr3,length=198022430,assembly=hg19>
##contig=<ID=chr4,length=191154276,assembly=hg19>
##contig=<ID=chr5,length=180915260,assembly=hg19>
##contig=<ID=chr6,length=171115067,assembly=hg19>
##contig=<ID=chr7,length=159138663,assembly=hg19>
##contig=<ID=chr8,length=146364022,assembly=hg19>
##contig=<ID=chr9,length=141213431,assembly=hg19>
##contig=<ID=chr10,length=135534747,assembly=hg19>
##contig=<ID=chr11,length=135006516,assembly=hg19>
##contig=<ID=chr12,length=133851895,assembly=hg19>
##contig=<ID=chr13,length=115169878,assembly=hg19>
##contig=<ID=chr14,length=107349540,assembly=hg19>
##contig=<ID=chr15,length=102531392,assembly=hg19>
##contig=<ID=chr16,length=90354753,assembly=hg19>
##contig=<ID=chr17,length=81195210,assembly=hg19>
##contig=<ID=chr18,length=78077248,assembly=hg19>
##contig=<ID=chr19,length=59128983,assembly=hg19>
##contig=<ID=chr20,length=63025520,assembly=hg19>
##contig=<ID=chr21,length=48129895,assembly=hg19>
##contig=<ID=chr22,length=51304566,assembly=hg19>
##contig=<ID=chrX,length=155270560,assembly=hg19>
##contig=<ID=chrY,length=59373566,assembly=hg19>
##contig=<ID=chrM,length=16571,assembly=hg19>
##reference=file:///home/u0028003/PIs/Neklason/Alignments/hg19.fasta
##source=SelectVariants
#CHROM	POS	ID	REF	ALT	QUAL	FILTER	INFO	FORMAT	carcinoid5	carcinoid6	carcinoid7	colon1	colon2	colon3	colon4
chr1	54708	.	G	C	51.35	PASS	AC=3;AF=0.750;AN=4;BaseQRankSum=0.736;DP=3;Dels=0.00;FS=4.771;HaplotypeScore=0.4999;MLEAC=3;MLEAF=0.750;MQ=70.00;MQ0=0;MQRankSum=0.736;QD=17.12;ReadPosRankSum=0.736;VQSLOD=10.70;culprit=MQ	GT:AD:DP:GQ:PL	./.	0/1:1,1:2:34:36,0,34	./.	1/1:0,1:1:3:41,3,0	./.	./.	./.
chr1	714427	rs12028261	G	A	57.36	PASS	AC=4;AF=1.00;AN=4;DB;DP=2;Dels=0.00;FS=0.000;HaplotypeScore=0.0000;MLEAC=4;MLEAF=1.00;MQ=70.00;MQ0=0;QD=28.68;VQSLOD=22.08;culprit=HaplotypeScore	GT:AD:DP:GQ:PL	./.	1/1:0,1:1:3:39,3,0	./.	./.	./.	./.	1/1:0,1:1:3:41,3,0
chr1	725822	rs199845677	G	A	45.59	PASS	AC=1;AF=0.167;AN=6;BaseQRankSum=-1.231;DB;DP=5;Dels=0.00;FS=3.979;HaplotypeScore=0.3333;MLEAC=1;MLEAF=0.167;MQ=70.00;MQ0=0;MQRankSum=0.358;QD=15.20;ReadPosRankSum=0.358;VQSLOD=11.31;culprit=HaplotypeScore	GT:AD:DP:GQ:PL	0/0:1,0:1:3:0,3,42	0/1:1,2:3:32:72,0,32	./.	./.	./.	0/0:1,0:1:3:0,3,43	./.

 */
public class MultiSampleVCFParser {
	//user defined fields
	private File vcfFile;
	
	//internal fields
	private MultiSampleVCFRecord[] vcfRecords;
	private String[] comments;
	public static final Pattern TAB = Pattern.compile("\\t");
	public static final Pattern COLON = Pattern.compile(":");
	private ArrayList<String> badVcfRecords = new ArrayList<String>();
	
	//indexs for ripping vcf records
	int chromosomeIndex= 0;
	int positionIndex = 1;
	int referenceIndex = 3;
	int alternateIndex = 4;
	int qualityIndex = 5;
	int filterIndex = 6;
	int infoIndex = 7;
	int formatIndex = 8;
	int firstSampleIndex = 9;
	int minimumNumberFields = 10;
	int numberFields = 0;
	
	//indexes for ripping the vcf sample GT:AD:DP:GQ:PL
	/*
	##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">
	##FORMAT=<ID=AD,Number=.,Type=Integer,Description="Allelic depths for the ref and alt alleles in the order listed">
	##FORMAT=<ID=DP,Number=1,Type=Integer,Description="Approximate read depth (reads with MQ=255 or with bad mates are filtered)">
	##FORMAT=<ID=GQ,Number=1,Type=Integer,Description="Genotype Quality">
	##FORMAT=<ID=PL,Number=G,Type=Integer,Description="Normalized, Phred-scaled likelihoods for genotypes as defined in the VCF specification">
	*/
	private String[] sampleNames = null;
	String expectedSampleFormat = "GT:AD:DP:GQ:PL";
	int numberFieldsInSample = 5;
	int sampleGenotypeGTIndex = 0;
	int sampleAllelicDepthADIndex =1;
	int sampleReadDepthDPIndex =2;
	int sampleGenotypeQualityGQIndex=3;
	int samplePhredLikelihoodsPLIndex = 4;

	public MultiSampleVCFParser(File vcfFile, boolean loadRecords){
		this. vcfFile = vcfFile;
		parseVCF(loadRecords);
	}
	
	

	public void parseVCF(boolean loadRecords) {
		BufferedReader in = null;
		String line = null;
		int badCounter = 0;
		try {
			in  = IO.fetchBufferedReader(vcfFile);
			ArrayList<MultiSampleVCFRecord> records = new ArrayList<MultiSampleVCFRecord>(10000);
			ArrayList<String> commentsAL = new ArrayList<String>();

			//find "#CHROM" line and parse sample names
			while ((line=in.readLine()) != null){
				//comments
				if (line.startsWith("#")){
					commentsAL.add(line);
					if (line.startsWith("#CHROM")) {
						String[] header = TAB.split(line);
						sampleNames = new String[header.length - firstSampleIndex];
						int index =0;
						for (int i=firstSampleIndex; i< header.length; i++) sampleNames[index++] = header[i];
						break;
					}
				}
			}
			if (sampleNames == null) throw new Exception("\nFailed to find the #CHROM header line.");
			comments = new String[commentsAL.size()];
			commentsAL.toArray(comments);
			
			//load data?
			if (loadRecords == false) return;
			
			//parse data
			while ((line=in.readLine()) != null){
				try {
					MultiSampleVCFRecord vcf = new MultiSampleVCFRecord(line, this);
					records.add(vcf);
				} catch (Exception e) {
					System.err.println("Skipping malformed VCF Record-> "+line);
					System.err.println("Error-> "+e.getMessage());
					if (badCounter++ > 100) {
						throw new Exception("\nToo many malformed VCF Records.\n");
					}
					badVcfRecords.add(line);
				}
			}

			
			//save array
			vcfRecords = new MultiSampleVCFRecord[records.size()];
			records.toArray(vcfRecords);

			
		}catch (Exception e) {
			System.err.println("\nAborting, problem parsing vcf file -> "+vcfFile);
			e.printStackTrace();
			System.exit(1);
		} finally{
			try {
				in.close();
			} catch (IOException e) {}
		}
	}
	
	public int countMatchingVCFRecords(String matchFilterText){
		int numMatches = 0;
		for (MultiSampleVCFRecord r : vcfRecords){
			if (r.getFilter().equals(matchFilterText)) numMatches++;
		}
		return numMatches;
	}
	
	/**Sets the filter field in each record to the indicated text.*/
	public void setFilterFieldOnAllRecords (String text){
		for (MultiSampleVCFRecord r : vcfRecords) r.setFilter(text);
	}

	public MultiSampleVCFRecord[] getVcfRecords() {
		return vcfRecords;
	}

	public void setVcfRecords(MultiSampleVCFRecord[] vcfRecords) {
		this.vcfRecords = vcfRecords;
	}

	public String[] getComments() {
		return comments;
	}

	public void setComments(String[] comments) {
		this.comments = comments;
	}

	public String[] getSampleNames() {
		return sampleNames;
	}

	public void setSampleNames(String[] sampleNames) {
		this.sampleNames = sampleNames;
	}

	/**Prints out two gzipped vcf files with records that match the fieldPass and those that don't.
	 * Note, the original, unmodified record is printed in either case.*/
	public void printRecords(String fieldPass) {
		try {
			String fullPathName = Misc.removeExtension(vcfFile.getCanonicalPath());
			File good = new File(fullPathName+ "_Pass.vcf.gz");
			File bad = new File(fullPathName+ "_Fail.vcf.gz");
			Gzipper outGood = new Gzipper(good);
			Gzipper outBad = new Gzipper(bad);
			outGood.println(comments);
			outBad.println(comments);
			for (MultiSampleVCFRecord r : vcfRecords){
				if (r.getFilter().equals(fieldPass)) outGood.println(r);
				else outBad.println(r);
			}
			
			outGood.close();
			outBad.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}

	public ArrayList<String> getBadVcfRecords() {
		return badVcfRecords;
	}

	

}
