package edu.utah.seq.data.sam;

import java.util.ArrayList;
import java.util.regex.*;

import util.bio.seq.Seq;
import util.gen.*;

/**Light weight container for a sam alignment representing one line in a sam record. see http://samtools.sourceforge.net/SAM1.pdf
 * Note, interbase coordinates, zero start, end excluded.  This is opposite of the sam format specification.*/
public class SamAlignment {

	//fields
	private String name;
	private short flags;
	private String referenceSequence;
	/**Interbase coordinates, this is the start position of the ALIGNMENT, NOT read, thus masking (H or S) has shifted the position relative to the start of the read.*/
	private int position;
	/**Mapping Quality (phred-scaled posterior probability that the mapping position of this read is incorrect), bigger the value the less likely it is mis aligned.*/
	private int mappingQuality;
	private String cigar;
	private String mateReferenceSequence;
	private int matePosition;
	private int inferredInsertSize;
	private String sequence;
	private String qualities;
	private String[] tags = null;
	private String md = null;
	private String unmodifiedSamRecord;
	private boolean spliceJunction = false;
	private boolean convertedJunctionCoordinates = false;
	private int misc;

	//static patterns
	private static final Pattern TAB = Pattern.compile("\t");
	private static final Pattern SPLICE_JUNCTION = Pattern.compile("(.+)_(\\d+)_(\\d+)");
	private static final Pattern NORMAL_CIGAR = Pattern.compile("^\\d+M$");
	/**Alignment score generated by aligner.*/
	private static final Pattern AS = Pattern.compile("AS:i:(\\d+)");
	private static final Pattern NON_GATC = Pattern.compile("[^GATCgatc]");
	private static final Pattern digitChrom = Pattern.compile("\\d+");
	private static final Pattern COLON = Pattern.compile(":");
	private static final Pattern UNDER_SCORE = Pattern.compile("_");
	private static final Pattern MINUS = Pattern.compile("-");
	public static final Pattern CIGAR_SUB = Pattern.compile("(\\d+)([MSNIDH])");
	private static final Pattern CIGAR_COUNTS = Pattern.compile("(\\d+)[MDN]");
	private static final Pattern CIGAR_SOFT = Pattern.compile("(\\d+)S");
	private static final Pattern CIGAR_SOFT_RIGHT = Pattern.compile(".+M(\\d+)S$");
	private static final Pattern CIGAR_SOFT_LEFT = Pattern.compile("^(\\d+)S.+");
	private static final Pattern CIGAR_COUNTS_MIN = Pattern.compile("(\\d+)[MIN]");
	private static final Pattern CIGAR_BAD_CHARACTERS = Pattern.compile(".*[^\\dMNIDSH].*");
	public static final Pattern CIGAR_STARTING_MASK = Pattern.compile("^(\\d+)[SH].+");
	public static final Pattern CIGAR_STOP_MASKED= Pattern.compile(".+\\D(\\d+)[SH]$");
	public static final Pattern CIGAR_STARTING_HARD_MASK = Pattern.compile("^(\\d+)[H].+");
	public static final Pattern CIGAR_STOP_HARD_MASK= Pattern.compile(".+\\D(\\d+)[H]$");
	public static final Pattern BAD_NAME = Pattern.compile("(.+)/([12])$");
	
	//private boolean debug = true;



	//constructors
	public SamAlignment(){}

	public SamAlignment (String line, boolean fixNonChrChroms) throws MalformedSamAlignmentException, NumberFormatException{
		unmodifiedSamRecord = line;
		String[] tokens = TAB.split(line);
		//check length
		if (tokens.length < 11 ) throw new MalformedSamAlignmentException("Cannot parse SamAlignment, too few columns, requires a minimum of 11 -> <QNAME> <FLAG> <RNAME> <POS> <MAPQ> <CIGAR> <MRNM> <MPOS> <ISIZE> <SEQ> <QUAL>  [<TAG>:<VTYPE>:<VALUE> [...]]");
		//assign <QNAME> <FLAG> <RNAME> <POS> <MAPQ> <CIGAR> <MRNM> <MPOS> <ISIZE> <SEQ> <QUAL>  [<TAG>:<VTYPE>:<VALUE> [...]]
		name = tokens[0];
		flags = Short.parseShort(tokens[1]);
		//check name for /1 or /2 and remove, this is part of the flag
		Matcher mat = BAD_NAME.matcher(name);
		if (mat.matches()) {
			name = mat.group(1);
		}
		referenceSequence = tokens[2];
		if (fixNonChrChroms) referenceSequence = fixChromosomeName(referenceSequence);
		//note subtracting 1 to put into interbase coordinates
		position = Integer.parseInt(tokens[3])-1;
		mappingQuality = Integer.parseInt(tokens[4]);
		cigar = tokens[5];		
		mateReferenceSequence = tokens[6];
		if (fixNonChrChroms) mateReferenceSequence = fixChromosomeName(mateReferenceSequence);
		matePosition = Integer.parseInt(tokens[7])-1;
		inferredInsertSize = Integer.parseInt(tokens[8]);
		sequence = tokens[9];
		qualities = tokens[10];
		//check lengths
		if (sequence.length() != qualities.length()) throw new MalformedSamAlignmentException("Cannot parse SamAlignment, sequence length does not match quality string length see -> "+line);
		//tags
		if (tokens.length > 11){
			int numberTags = tokens.length - 11;
			tags = new String[numberTags];
			int counter = 0;
			for (int i=11; i< tokens.length; i++) tags[counter++] = tokens[i];
		}
	}

	//methods
	
	/**Takes the original fastq sequence and formats it to match this cigar and orientation.*/
	public String processOriginalSequence(String originalSequence){
		String fixedSeq = new String (originalSequence);
		//reverse sequence?
		if (isReverseStrand()) {
			fixedSeq = Seq.reverseComplementDNA(originalSequence);
		}
		//trim?
		if (cigar.contains("H")){
			//at start?
			Matcher mat = CIGAR_STARTING_HARD_MASK.matcher(cigar);
			if (mat.matches()){
				int basesToTrim = Integer.parseInt(mat.group(1));
				fixedSeq = fixedSeq.substring(basesToTrim);
			}
			//at end?
			mat = CIGAR_STOP_HARD_MASK.matcher(cigar);
			if (mat.matches()){
				int basesToTrim = Integer.parseInt(mat.group(1));
				int stopIndex = fixedSeq.length() - basesToTrim;
				fixedSeq = fixedSeq.substring(0, stopIndex);
			}
		}
		return fixedSeq;
	}
	
	/**For reads with H or S masking, strips off these notations from the CIGAR and for S trims the read sequence and base qualities. H's are already trimmed.*/
	public void trimMaskingOfReadToFitAlignment(){
		//remove hard masking references in cigar
		if (cigar.contains("H")){
			StringBuilder sb = new StringBuilder();
			//(\\d+)([MSIDH])
			Matcher mat = CIGAR_SUB.matcher(cigar);
			while (mat.find()){
				if (mat.group(2).equals("H") == false) sb.append(mat.group(0));
			}
			cigar = sb.toString();
		}
		
		//look for soft masking
		if (cigar.contains("S")){
			//at beginning? ^(\\d+)[SH].+
			Matcher mat = CIGAR_STARTING_MASK.matcher(cigar);
			if (mat.matches()){
				int basesToTrim = Integer.parseInt(mat.group(1));
				sequence = sequence.substring(basesToTrim);
				qualities = qualities.substring(basesToTrim);
				cigar = cigar.substring(mat.group(1).length()+1);
			}
			
			//at end?  .+\\D(\\d+)[SH]$
			mat = CIGAR_STOP_MASKED.matcher(cigar);
			if (mat.matches()){
				int basesToTrim = Integer.parseInt(mat.group(1));
				int stopIndex = sequence.length() - basesToTrim;
				sequence = sequence.substring(0, stopIndex);
				qualities = qualities.substring(0, stopIndex);
				stopIndex = cigar.length() - (mat.group(1).length() +1);
				cigar = cigar.substring(0, stopIndex);
			}
		}
	}

	/**Looks for chromosomes named 1,2,3,MT,X,Y and converts to chr1,chr2,chr3,chrM,chrY,chrX*/
	public String fixChromosomeName(String chr){
		if (chr.equals("=")) return chr;
		if (digitChrom.matcher(chr).matches() || chr.length() == 1) return "chr"+chr;
		if (chr.equals("MT") || chr.equals("chrMT")) return "chrM";
		return chr;
	}

	/**Coordinates are returned in the sam spec 1 base notation.*/
	public String toString(){
		StringBuilder sb = new StringBuilder();
		sb.append(name);
		sb.append("\t");
		sb.append(flags);
		sb.append("\t");
		sb.append(referenceSequence);
		sb.append("\t");
		sb.append(position+1);
		sb.append("\t");
		sb.append(mappingQuality);
		sb.append("\t");
		sb.append(cigar);
		sb.append("\t");
		sb.append(mateReferenceSequence);
		sb.append("\t");
		sb.append(matePosition+1);
		sb.append("\t");
		sb.append(inferredInsertSize);
		sb.append("\t");
		sb.append(sequence);
		sb.append("\t");
		sb.append(qualities);
		if (tags != null){
			for (int i=0; i< tags.length; i++){
				sb.append("\t");
				sb.append(tags[i]);
			}
		}
		return sb.toString();
	}

	/**Coordinates are returned in the sam spec 1 base notation.*/
	public String toStringNoMDField(){
		StringBuilder sb = new StringBuilder();
		sb.append(name);
		sb.append("\t");
		sb.append(flags);
		sb.append("\t");
		sb.append(referenceSequence);
		sb.append("\t");
		sb.append(position+1);
		sb.append("\t");
		sb.append(mappingQuality);
		sb.append("\t");
		sb.append(cigar);
		sb.append("\t");
		sb.append(mateReferenceSequence);
		sb.append("\t");
		sb.append(matePosition+1);
		sb.append("\t");
		sb.append(inferredInsertSize);
		sb.append("\t");
		sb.append(sequence);
		sb.append("\t");
		sb.append(qualities);
		if (tags != null){
			for (int i=0; i< tags.length; i++){
				if (tags[i].startsWith("MD:")) continue;
				sb.append("\t");
				sb.append(tags[i]);
			}
		}
		return sb.toString();
	}

	/**Coordinates are returned in 1 base notation.*/
	public String toStringText(){
		StringBuilder sb = new StringBuilder();
		sb.append(NON_GATC.matcher(sequence).replaceAll("N"));
		sb.append("\t");
		sb.append(referenceSequence);
		sb.append("\t");
		sb.append(position+1);
		sb.append("\t");
		if (this.isReverseStrand()) sb.append("R");
		else sb.append("F");
		return sb.toString();
	}

	/**Converts alignment to unmapped*/
	public void convertToUnmapped(){
		flags = 4;
		position = -1;
		referenceSequence = "*";
		mappingQuality = 0;
		cigar = "*";
		tags = new String[]{"ZS:Z:NM"};
	}

	/**This is a fixer for novoalign's sam output that sometimes assigns chromosome and position values to unmapped reads!*/
	public void fixUnMappedNovo(){
		//is mate mapped?
		if (isMateUnMapped() == false){
			//yes mate is mapped so watch out for = in mateReferenceSequence
			if (mateReferenceSequence.equals("=")) mateReferenceSequence = new String(referenceSequence);
		}
		position = -1;
		referenceSequence = "*";
		mappingQuality = 0;
		cigar = "*";
		inferredInsertSize = 0;
	}

	/**This is a fixer for novoalign's sam output that sometimes assigns chromosome and position values to unmapped reads!
	 * Must also change the bit flags!*/
	public void setUnMappedMate(){
		mateReferenceSequence = "*";
		matePosition = -1; //this will be incremented by 1
		inferredInsertSize = 0;
	}

	public boolean convertTranscriptomeAlignment(boolean tossMDAndRGTags) throws MalformedSamAlignmentException{	
		
		//split on :
		String[] segments = COLON.split(referenceSequence);
		String coordinatesString = null;
		String geneName = null;
		String transcriptName = null;
		String spliceJunctionName = null;

		if (segments.length == 3) {
			//Splice junction hit ENSDARG00000087418:chr20:6691-6707_9356-9386_9436-9463_9494-9513
			geneName = segments[0];
			referenceSequence = segments[1];
			coordinatesString = segments[2];
			spliceJunctionName = referenceSequence+":"+coordinatesString;
			spliceJunction = true;
		}
		else if (segments.length == 4) {
			//Transcript hit ENSDARG00000012493:ENSDART00000126849:chr20:705345-705376_708250-708344_710468-710532
			geneName = segments[0];
			transcriptName = segments[1];
			referenceSequence = segments[2];
			coordinatesString = segments[3];
		}

		//append tags, GeneName(GN:Z:), TranscriptName(TN:Z:), SpliceJunction(SJ:Z), remove MD and RG tags
		ArrayList<String> tagsAL = new ArrayList<String>();
		for (String tag : tags){
			if (tossMDAndRGTags && (tag.startsWith("MD:") || tag.startsWith("RG:")) ) continue;
			else tagsAL.add(tag);
		}
		if (geneName != null) tagsAL.add("GN:Z:"+geneName);
		if (transcriptName != null) tagsAL.add("TN:Z:"+transcriptName);
		if (spliceJunctionName != null) tagsAL.add("SJ:Z:"+spliceJunctionName);
		tags = new String[tagsAL.size()];
		tagsAL.toArray(tags);

		//fix mate info, novoalign assigns coordinates 
		if (isPartOfAPairedAlignment() == false || isMateUnMapped()) setUnMappedMate();

		//convert mate position?
		else if (isPartOfAPairedAlignment() && isMateUnMapped() == false){
			String[] mateSegments = COLON.split(mateReferenceSequence);
			if (mateSegments.length == 3) {
				//Splice junction hit ENSDARG00000087418:chr20:6691-6707_9356-9386_9436-9463_9494-9513
				mateReferenceSequence = mateSegments[1];
				if (convertTranscriptomePosition(mateSegments[2], true) == false) return false;
			}
			else if (mateSegments.length == 4) {
				//Transcript hit ENSDARG00000012493:ENSDART00000126849:chr20:705345-705376_708250-708344_710468-710532
				mateReferenceSequence = mateSegments[2];
				if (convertTranscriptomePosition(mateSegments[3], true) == false) return false;
			}
			//what about the insert size?
		}

		//convert position and cigar if a transcript hit otherwise its a genomic hit
		if (geneName != null) {
			convertedJunctionCoordinates = true;
			return convertTranscriptomePosition(coordinatesString, false);
		}

		return true;
	}
	
	/**Replaces or adds an NH:i tag denoting the number of times this read is aligned in the sam file, 1 = unique;
	 * NH:i:1 or more.*/
	public void addAlignmentCountTag (int numberAlignmentsForThisRead){
		ArrayList<String> tagsAL = new ArrayList<String>();
		//remove any existing NH tag
		for (String tag : tags){
			if (tag.startsWith("NH:i") == false) tagsAL.add(tag);
		}
		//addit
		tagsAL.add("NH:i:"+numberAlignmentsForThisRead);
		tags = new String[tagsAL.size()];
		tagsAL.toArray(tags);
	}
	
	/**Replaces or adds a MP:A: tag denoting whether it was merged T or failed merging F.*/
	public void addMergeTag (boolean successfullyMerged){
		ArrayList<String> tagsAL = new ArrayList<String>();
		//remove existing
		for (String tag : tags){
			if (tag.startsWith("MP:A:") == false) tagsAL.add(tag);
		}
		//add new
		if (successfullyMerged) tagsAL.add("MP:A:T");
		else tagsAL.add("MP:A:F");
		tags = new String[tagsAL.size()];
		tagsAL.toArray(tags);
	}

	/**Looks for the novoalign bisulfite alignment tag ZB:Z:GA or ZB:Z:CT, returns 0 if not found 1 for GA, 2 for CT.*/
	public int getCtGaTag (){
		for (String tag : tags){
			if (tag.equals("ZB:Z:GA")) return 1;
			else if (tag.equals("ZB:Z:CT")) return 2;
		}
		return 0;
	}
	
	/**Looks for the splice junction alignment tag SJ:Z:xxxxxx returns null if not found or the associated splice junction info, e.g. chr20:744550-744647_745851-745948 */
	public String getSJTagValue (){
		for (String tag : tags){
			if (tag.startsWith("SJ:Z:")) return tag.substring(5);
		}
		return null;
	}
	
	/**Looks for the polyA tag At:i:### returns null if not found or the length of polyA from the tag, e.g. At:i:9 will return 9 for the length of the polyA tag */
	public Integer getAtPolyATagValue (){
		Integer pALength;
		for (String tag : tags){
			if (tag.startsWith("At:i:")) {
				String pAlength = tag.substring(5);
				pALength = Integer.parseInt(pAlength);
				return pALength;
			}
		}
		return null;
	}
	
	/**remove polyA tag from rest of tags*/
	public void removePolyAtag(){
		ArrayList<String> tempTags = new ArrayList<String>();
		for (String tag : tags){
			if (tag.startsWith("At:i:")) {
				//do not copy
			}else{
				tempTags.add(tag);
			}
		}
		//overwrite the tags array to delete the polyA tag
		tags = tempTags.toArray(new String[tempTags.size()]);
	}

	private boolean convertTranscriptomePosition(String coordinatesString, boolean convertMatePosition){
		//split coordinates on underscore to get chunks
		String[] coordinates = UNDER_SCORE.split(coordinatesString);

		//convert into start stops
		int[][] startStop = new int[coordinates.length][2];
		for (int i=0; i< coordinates.length; i++){
			String[] ss = MINUS.split(coordinates[i]);
			startStop[i] = new int[]{Integer.parseInt(ss[0]), Integer.parseInt(ss[1])};
		}

		//find genomic position for reference
		int seqLengthToCover = position;
		if (convertMatePosition) seqLengthToCover = matePosition;
		for (int[] ss: startStop){
			int length = ss[1]-ss[0];
			//contained?
			if (length > seqLengthToCover){
				if (convertMatePosition) matePosition = ss[0]+seqLengthToCover;
				else position = ss[0]+seqLengthToCover;
				break;
			}
			else seqLengthToCover -= length;
		}

		//modify cigar
		if (convertMatePosition) return true;
		else return convertTranscriptomeCigar(startStop);
	}

	/*Assumes position has been converted to genomic coordinates*/
	private boolean convertTranscriptomeCigar(int[][] startStop){
		//check to see if cigar contains any unsupported characters
		Matcher mat = CIGAR_BAD_CHARACTERS.matcher(cigar);
		if (mat.matches()) {
			System.err.println("\nUnsupported cigar string "+cigar);
			System.out.println(this);
			System.exit(0);
			return false;
		}

		//set length
		int startPosition = position;
		StringBuffer cigarSB = new StringBuffer();

		//for each block
		mat = CIGAR_SUB.matcher(cigar);
		while (mat.find()){
			String call = mat.group(2);
			int numberBases = Integer.parseInt(mat.group(1));

			//soft mask? no need to change position
			if (call.equals("S")) {
				cigarSB.append(mat.group());
			}
			//a match
			else if (call.equals("M")) {
				CigarPosition cigarPosition = processTranscriptomeCigar(startStop, numberBases, startPosition);
				if (cigarPosition == null) return false;
				cigarSB.append(cigarPosition.subCigar);
				startPosition = cigarPosition.nextPosition;
			}
			//an insertion, no need to change position, append N's though?
			else if (call.equals("I")) {
				cigarSB.append(mat.group());
				//add Ns for cases where the I occurred in the gap
				for (int i=0; i< startStop.length; i++){
					if (startPosition == startStop[i][0]){
						int len = startStop[i][0] - startStop[i-1][1];				
						cigarSB.append(len);
						cigarSB.append("N");
						break;
					}
				}
			}
			//a deletion
			else if (call.equals("D")) {	
				//add Ns for cases where D occurs on 1st base of next exon
				for (int i=0; i< startStop.length; i++){
					if (startPosition == startStop[i][0]){
						int len = startStop[i][0] - startStop[i-1][1];					
						cigarSB.append(len);
						cigarSB.append("N");
						break;
					}
				}
				
				cigarSB.append(mat.group());
				//fetch the ss
				int[] ss = null;
				int index = 0;
				for (; index< startStop.length; index++) {
					if (startStop[index][0]<= startPosition && startStop[index][1] > startPosition) {
						ss = startStop[index];
						break;
					}
				}
				if (ss == null) Misc.printErrAndExit("\nFailed to find an inter for D\n"+this);
				startPosition += 1;
				index++;
				if (ss[1] == startPosition && index < startStop.length) startPosition = startStop[index][0];
			}
			//a hardmask
			else if (call.equals("H")) cigarSB.append(mat.group());

			else Misc.printErrAndExit("\nUnsupported CIGAR string! "+this);
		}

		//assign
		cigar = cigarSB.toString();
		return true;
	}

	private class CigarPosition{
		String subCigar;
		int nextPosition;

		public CigarPosition(String subCigar, int nextPosition){
			this.subCigar = subCigar;
			this.nextPosition = nextPosition;
		}

		public String toString(){
			return subCigar+"\t"+nextPosition;
		}
	}

	private CigarPosition processTranscriptomeCigar(int[][] startStop, int seqLengthToCover, int startPosition){
		StringBuilder sb = new StringBuilder();

		//find starting chunk
		for (int i=0; i< startStop.length; i++){
			
			if (startStop[i][0]<= startPosition && startStop[i][1] > startPosition){

				//calc remaining bases in this ss
				int remainingBps = startStop[i][1] - startPosition;
				//fully contained?
				if (remainingBps >= seqLengthToCover){
					sb.append(seqLengthToCover);
					sb.append("M");
					int nextPosition = startPosition + seqLengthToCover;
					if (remainingBps == seqLengthToCover && (i+1) < startStop.length){
						nextPosition = startStop[i+1][0];
					}
					return new CigarPosition(sb.toString(), nextPosition);
				}

				//need more
				sb.append(remainingBps);
				sb.append("M");
				seqLengthToCover -= remainingBps;

				//tack on more matches
				for (int j=i+1; j< startStop.length; j++){
					//append gap
					int gap = startStop[j][0]- startStop[j-1][1];
					sb.append(gap);
					sb.append("N");

					//cal chunk length
					int basesAvailable = startStop[j][1]- startStop[j][0];
					//covered?
					if (basesAvailable >= seqLengthToCover){
						sb.append(seqLengthToCover);
						sb.append("M");
						int nextPosition = startStop[j][0] + seqLengthToCover ;
						if (basesAvailable == seqLengthToCover && (j+1) < startStop.length){
							nextPosition = startStop[j+1][0];
						}
						//return sb.toString();
						return new CigarPosition(sb.toString(), nextPosition);
					}
					//nope not covered thus tack on all and advance
					sb.append(basesAvailable);
					sb.append("M");
					seqLengthToCover -= basesAvailable;

				}

				Misc.printErrAndExit("Hmm should have returned! ");
			}

		}
		System.err.println("\n\nDidn't find an intersecting chunk?");
		return null;
	}


	/**Assumes interbase coordinates for splice junctions, only works for complete matches no S or H's in CIGAR*/
	public boolean checkAndConvertSpliceJunction(int junctionRadius) throws MalformedSamAlignmentException{
		Matcher m = SPLICE_JUNCTION.matcher(referenceSequence);
		boolean converted = false;
		if (m.matches()){
			//check CIGAR
			//TODO: Should figure out how to eliminate this
			if (NORMAL_CIGAR.matcher(cigar).matches()== false) {
				//System.err.println("Skipping, unsupported CIGAR-> "+toString());
				return false;
			}

			//check position
			int leftM = junctionRadius  -position;

			//reset chromosome
			referenceSequence = m.group(1);

			//parse left and right sides of junction
			int left = Integer.parseInt(m.group(2));
			int right = Integer.parseInt(m.group(3));

			//set cigar
			int gap = right-left;
			int rightM = sequence.length()-leftM;
			cigar = leftM+"M"+gap+"N"+rightM+"M";

			//reset start position
			position = left - junctionRadius + position;
			converted = true;
		}
		//check mate
		m = SPLICE_JUNCTION.matcher(mateReferenceSequence);
		if (m.matches()){
			//reset chromosome
			mateReferenceSequence = m.group(1);
			//parse left side of junction
			int left = Integer.parseInt(m.group(2));
			//reset start position
			matePosition = left - junctionRadius + matePosition;
			converted = true;
		}
		//reset insert size?
		if (converted && matePosition > 0){
			inferredInsertSize = Math.abs(matePosition-position+sequence.length());
		}
		return converted;
	}

	/**Alignment score generated by aligner.  For novoalign, smaller is better alignment, bigger is worse alignment.
	 * @return Integer.MIN_VALUE if not found in tags.*/
	public int getAlignmentScore() throws NumberFormatException{
		if (tags == null) return Integer.MIN_VALUE;
		Matcher mat;
		for (int i=0; i< tags.length; i++){
			mat = AS.matcher(tags[i]);
			if (mat.matches()) return Integer.parseInt(mat.group(1));
		}
		return Integer.MIN_VALUE;
	}

	/**For sorting.*/
	public boolean equals(Object o){
		SamAlignment sam = (SamAlignment) o;
		String thisX = referenceSequence+ position+ cigar+ isFirstPair();
		String otherX = sam.referenceSequence + sam.position + sam.cigar+ sam.isFirstPair();
		return thisX.equals(otherX);
	}
	/**For sorting.*/
	public int hashCode(){
		String thisX = referenceSequence+ position+ cigar+ isFirstPair();
		return thisX.hashCode();
	}

	
	/**Counts the number of genomic bps covered by the cigar string. Only counts M D and N.*/
	public int countLengthOfAlignment (){
		return countLengthOfAlignment(cigar);
	}

	/**Counts the number of genomic bps covered by the cigar string. Only counts M D and N.*/
	public static int countLengthOfAlignment (String cigar){
		int length = 0;
		//for each M D or N block
		Matcher mat = CIGAR_COUNTS.matcher(cigar);
		while (mat.find()){
			length += Integer.parseInt(mat.group(1));
		}
		return length;
	}
	
	/**Counts the number of things in CIGAR, H,S,M,D,I,N .*/
	public static int countLengthOfCIGAR (String cigar){
		int length = 0;
		Matcher mat = CIGAR_SUB.matcher(cigar);
		while (mat.find()) length += Integer.parseInt(mat.group(1));
		return length;
	}
	
	/**Counts the number of MIN bases in the cigar string. This is the length of the insert for merged pairs.*/
	public static int countLengthOfCigarMIN (String cigar){
		int length = 0;
		//for each M I or N block
		Matcher mat = CIGAR_COUNTS_MIN.matcher(cigar);
		while (mat.find()){
			length += Integer.parseInt(mat.group(1));
		}
		return length;
	}
	
	/**Counts the number of soft masked bases in CIGAR.*/
	public int countLengthOfSoftMaskedBases (){
		int length = 0;
		//for each S block
		Matcher mat = CIGAR_SOFT.matcher(cigar);
		while (mat.find()){
			length += Integer.parseInt(mat.group(1));
		}
		return length;
	}
	
	/**Counts the number of soft masked bases in CIGAR either at the start/left or the end/right.*/
	public int countLengthOfSidedSoftMaskedBases (boolean countLeft){
		int length = 0;
		//for each S block
		Matcher mat;
		if (countLeft) mat = CIGAR_SOFT_LEFT.matcher(cigar);
		else mat = CIGAR_SOFT_RIGHT.matcher(cigar);
		if (mat.matches()) length = Integer.parseInt(mat.group(1));
		return length;
	}
	
	/**Replaces soft masked bases with matches for display in IGB*/
	public void deSoftMaskCigar() {
		StringBuffer cigarSB = new StringBuffer();

		//for each block
		Matcher mat = CIGAR_SUB.matcher(cigar);
		while (mat.find()){
			String call = mat.group(2);
			//soft mask? Replace with M
			if (call.equals("S")) {
				int numberBases = Integer.parseInt(mat.group(1));
				cigarSB.append(numberBases);
				cigarSB.append("M");
			}
			else cigarSB.append(mat.group());
		}

		//assign
		cigar = cigarSB.toString();
		
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
	
	/**If cigar starts with a hard or soft mask, the number bases masked is subtracted from the position, otherwise just returns the position.*/
	public int getUnclippedStart() {
		Matcher m = CIGAR_STARTING_MASK.matcher(cigar);
		if (m.matches() == false) return position;
		int num = Integer.parseInt(m.group(1));
		return position - num;
	}
	

	/**
	 * Index	Index^2	MethodName	Flag	DescriptionFromSamSpec
	 * 0	1	isReadPartOfAPairedAlignment	0x0001	the read is paired in sequencing, no matter whether it is mapped in a pair 
	 * 1	2	isReadAProperPairedAlignment	0x0002	the read is mapped in a proper pair (depends on the protocol, normally inferred during alignment) 1 
	 * 2	4	isQueryUnmapped	0x0004	the query sequence itself is unmapped 
	 * 3	8	isMateUnMapped	0x0008	the mate is unmapped  
	 * 4	16	isQueryReverseStrand	0x0010	strand of the query (false for forward; true for reverse strand) 
	 * 5	32	isMateReverseStrand	0x0020	strand of the mate 
	 * 6	64	isReadFirstPair	0x0040	the read is the �rst read in a pair 
	 * 7	128	isReadSecondPair	0x0080	the read is the second read in a pair 
	 * 8	256	isAlignmentNotPrimary	0x0100	the alignment is not primary (a read having split hits may have multiple primary alignment records) 
	 * 9	512	doesReadFailVendorQC	0x0200	the read fails platform/vendor quality checks 
	 * 10	1024	isReadADuplicate	0x0400	the read is either a PCR duplicate or an optical duplicate 
	 */
	boolean testBitwiseFlag(int testValue){
		return ((flags & testValue) == testValue);
	}
	/**The read is paired in sequencing, no matter whether it is mapped in a pair*/
	public boolean isPartOfAPairedAlignment(){
		return testBitwiseFlag(1);
	}
	/**The read is mapped in a proper pair (depends on the protocol, normally inferred during alignment)*/
	public boolean isAProperPairedAlignment(){
		return testBitwiseFlag(2);
	}
	/**The query sequence itself is unmapped*/
	public boolean isUnmapped(){
		return testBitwiseFlag(4);
	}
	/**The mate is unmapped*/
	public boolean isMateUnMapped(){
		return testBitwiseFlag(8);
	}
	/**Strand of the query (false for forward; true for reverse strand)*/
	public boolean isReverseStrand(){
		return testBitwiseFlag(16);
	}
	/**Strand of the mate*/
	public boolean isMateReverseStrand(){
		return testBitwiseFlag(32);
	}
	/**The read is the �rst read in a pair*/
	public boolean isFirstPair(){
		return testBitwiseFlag(64);
	}
	/**The read is the second read in a pair*/
	public boolean isSecondPair(){
		return testBitwiseFlag(128);
	}
	/**The alignment is not primary (a read having split hits may have multiple primary alignment records)*/
	public boolean isNotAPrimaryAlignment(){
		return testBitwiseFlag(256);
	}
	/**The read fails platform/vendor quality checks*/
	public boolean failedQC(){
		return testBitwiseFlag(512);
	}
	/**The read is either a PCR duplicate or an optical duplicate*/
	public boolean isADuplicate(){
		return testBitwiseFlag(1024);
	}

	public void printFlags(){
		System.out.println(flags+"\tflags");
		System.out.println(isPartOfAPairedAlignment()+"\tisPartOfAPairedAlignment()");
		System.out.println(isAProperPairedAlignment()+"\tisAProperPairedAlignment()");
		System.out.println(isUnmapped()+"\tisUnmapped()");
		System.out.println(isMateUnMapped()+"\tisMateUnMapped()");
		System.out.println(isReverseStrand()+"\tisReverseStrand()");
		System.out.println(isMateReverseStrand()+"\tisMateReverseStrand()");
		System.out.println(isFirstPair()+"\tisFirstPair()");
		System.out.println(isSecondPair()+"\tisSecondPair()");
		System.out.println(isNotAPrimaryAlignment()+"\tisNotAPrimaryAlignment()");
		System.out.println(failedQC()+"\tfailedQC()");
		System.out.println(isADuplicate()+"\tisADuplicate()");

	}

	//getters and setters
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public short getFlags() {
		return flags;
	}
	public void setFlags(short flags) {
		this.flags = flags;
	}
	public String getReferenceSequence() {
		return referenceSequence;
	}
	public void setReferenceSequence(String referenceSequence) {
		this.referenceSequence = referenceSequence;
	}
	public int getPosition() {
		return position;
	}
	public void setPosition(int position) {
		this.position = position;
	}
	/**Mapping Quality (phred-scaled posterior probability that the mapping position of this read is incorrect), bigger the value the less likely it is mis aligned.*/
	public int getMappingQuality() {
		return mappingQuality;
	}
	
	/**Looks for the MD:Z: field in the tags. Returns null if not found.*/
	public String getMD(){
		if (md == null) {
			//look for MD:Z:26G15T8G49 in tags
			for (String tag : tags){
				if (tag.startsWith("MD:Z:")){
					md = tag;
					break;
				}
			}
		}
		return md;
	}
	
	public void removeMD(){
		ArrayList<String> goodTags = new ArrayList<String>();
		for (String tag : tags){
			if (tag.startsWith("MD:") == false) goodTags.add(tag);
		}
		int numTags = goodTags.size();
		if (numTags != tags.length) {
			tags = new String[numTags];
			goodTags.toArray(tags);
		}
	}
	public void setMappingQuality(int mappingQuality) {
		this.mappingQuality = mappingQuality;
	}
	public String getCigar() {
		return cigar;
	}
	public void setCigar(String cigar) {
		this.cigar = cigar;
	}
	public String getMateReferenceSequence() {
		return mateReferenceSequence;
	}
	public void setMateReferenceSequence(String mateReferenceSequence) {
		this.mateReferenceSequence = mateReferenceSequence;
	}
	public int getMatePosition() {
		return matePosition;
	}
	public void setMatePosition(int matePosition) {
		this.matePosition = matePosition;
	}
	public int getInferredInsertSize() {
		return inferredInsertSize;
	}
	public void setInferredInsertSize(int inferredInsertSize) {
		this.inferredInsertSize = inferredInsertSize;
	}
	public String getSequence() {
		return sequence;
	}
	public void setSequence(String sequence) {
		this.sequence = sequence;
	}
	public String getQualities() {
		return qualities;
	}
	public void setQualities(String qualities) {
		this.qualities = qualities;
	}
	public String[] getTags() {
		return tags;
	}
	public void setTags(String[] tags) {
		this.tags = tags;
	}

	public boolean isSpliceJunction() {
		return spliceJunction;
	}

	public void setSpliceJunction(boolean spliceJunction) {
		this.spliceJunction = spliceJunction;
	}

	public boolean isConvertedJunctionCoordinates() {
		return convertedJunctionCoordinates;
	}

	public String getUnmodifiedSamRecord() {
		return unmodifiedSamRecord;
	}

	public int getMisc() {
		return misc;
	}

	public void setMisc(int misc) {
		this.misc = misc;
	}

	


}