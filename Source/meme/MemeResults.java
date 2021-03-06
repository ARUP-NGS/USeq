package meme;
import java.util.*;
import java.io.*;
import java.text.*;

/**Holds the results from various parts of MemeR.*/
public class MemeResults {
    
    //fields
    //screen dump and subsections
    StringBuffer results = new StringBuffer();  //screen dump of everything
    StringBuffer memeParserReports = new StringBuffer(); //subsection relating to parsing the meme reports
    StringBuffer memeReports = new StringBuffer(); //subsection contains all the motif info generated by meme, matrix tables for all the motifs found in all the files
    StringBuffer motifSearchResults = new StringBuffer();  //subsection containing all the motif search results, all the hits to each motif
    String motifSummary; //subsection containing a summary of the motifs generated by meme
    String motifHitsSummary; //subsection containing a summary of all the motif hits stats
    String motifHitPlot; //subsection containing the plot of the percentage of seq hits to each motif to each file
    String fileBaseName; //file base text to be used in writing out results files, with other text too
    String memeCmdLnArgs; //command line arguments used in firing the meme program, with other text too
    String resultsDirectory;  //directory where results files are to be written, with other text too
    
    //misc
    ArrayList selexOligoFiles = new ArrayList();  //array of files entered by user
    ArrayList motifSearchResultObjs = new ArrayList(); //array of MotifSearchResult objects generated by taking each motif generated
    //from each file and searching all the sequences in all the files, use these to get all the info desired
    MotifSearchResult[] msro; //ditto but an array
    MemeParser[] memeParsers; //a ref to the Array of MemeParsers generated by MemeR
    HashMap fileNameNumberHash; //hash containing a key value set linking a particular filename to a number
    int[] percents; //array to hold percent #s for plotting
    
    //primary methods
    
    /**Method to return a summary of all the motifs generated by MemeR, modify this to build an HTML table*/
    public String getMotifSummary(){
        //make hash
        makeFileNameHash();
        //make report
        StringBuffer data = new StringBuffer(
        "\n\n*************************************************************************************\n"+
        "Motif Summary:\n"+
        "*************************************************************************************\n"+
        "Meme Command Line Arguments:\n   "+memeCmdLnArgs +"\n\n"+
        "File Number: \tFile Name: \t\t Number of Motifs Found:\n");
        
        for (int i=0; i<memeParsers.length; i++){
            String fileName = memeParsers[i].getParsedFileName();
            data.append(
            "      "+fileNameNumberHash.get(fileName)+" \t"+fileName+" \t"+memeParsers[i].getNumMotifs()+"\n");
            
        }
        return data.toString();
    }
    
    /**method to sort the array of MotifSearchResult objects, also sets two fields based on the this.fileNameHash*/
    public void sortMotifSearchResults(){
        //convert ArrayList to an array
        msro = new MotifSearchResult[motifSearchResultObjs.size()];
        motifSearchResultObjs.toArray(msro);
        for (int i = 0; i<msro.length; i++){
            //get numberName of searched seq file
            File file = new File(msro[i].getSearchedSeqFile());
            Integer searchedFileNum = (Integer)fileNameNumberHash.get(file.getName());
            msro[i].setFileNumber(searchedFileNum.intValue());
            //get motif signature
            MemeMotif mm = msro[i].getMemeMotif();
            int numberName = mm.getNumberName();
            MemeParser mp = mm.getMemeParser();
            Integer fileNumber = (Integer)fileNameNumberHash.get(mp.getParsedFileName());
            String sig = fileNumber+"."+numberName;
            msro[i].setMotifSig(Double.parseDouble(sig));
        }
        Arrays.sort(msro);
    }
    
    /**Method to return a summary of all the motif hits to each file, modify this to build an HTML table*/
    public String getMotifHitsSummary(){
        //be sure to call makeFileNameHash() prior to running this method
        //sort msro, this sets some fields needed below
        sortMotifSearchResults();
        
        StringBuffer data = new StringBuffer(
        "\n*************************************************************************************\n"+
        "Motif Hit Summary:\n"+
        "*************************************************************************************\n"+
        "Row\tSrched\tMotif \t Meme \t% Seqs\t# Seqs\tAddOne\t% Seqs\t# Seqs\n"+
        " # \t File \tFile#.\tLLPSPM\tAbove \tAbove \tLLPSPM\tAbove \tAbove\n"+
        "   \t  #   \tMotif#\tCutOff\tCutoff\tCutOff\tCutOff\tCutoff\tCutOff\n");
        
        //walk through each motif
        //array to hold percent #s for plotting
        percents = new int[(msro.length)*2]; //meme hits, addOne hits
        NumberFormat formater = NumberFormat.getNumberInstance();
        int rowNumber = 1;
        for (int i = 0; i<msro.length; i++){
            //get numberName of searched seq file
            int searchedFileNum = msro[i].getFileNumber();
            //get motif signature
            double sig = msro[i].getMotifSig();
            //get cutoffs
            formater.setMaximumFractionDigits(2);
            String memeCutOff = formater.format(msro[i].getMemeLLPSPMCutOffScore());
            String AddOneCutOff = formater.format(msro[i].getAddOneLLPSPMCutOffScore());
            //get hit numbers
            int numHitsMeme = msro[i].getNumHitsMemeLLPSPM();
            int numHitsAddOne = msro[i].getNumHitsAddOneLLPSPM();
            //get total number of seqs in file
            int numSeqsSearched = msro[i].getnumSeqsSearched();
            //percent hits
            double perMeme = 100*(double)numHitsMeme/(double)numSeqsSearched;
            double perAddOne = 100*(double)numHitsAddOne/(double)numSeqsSearched;
            //save percents
            int A = rowNumber-1;
            int B = rowNumber;
            percents[A]= (int)Math.round(perMeme);
            percents[B]= (int)Math.round(perAddOne);
            formater.setMaximumFractionDigits(1);
            String percMeme = formater.format(perMeme);
            String percAddOne = formater.format(perAddOne);
            
            //text numbers
            String meme = numHitsMeme+"/"+numSeqsSearched;
            String addOne = numHitsAddOne+"/"+numSeqsSearched;
            
            data.append(rowNumber+","+ (++rowNumber)+"\t  "+searchedFileNum+"\t "+sig+"\t"+memeCutOff+"\t"+percMeme+"\t"+
            meme+"\t"+AddOneCutOff+"\t"+percAddOne+"\t"+addOne+"\n");
            rowNumber++;
            
        }
        return data.toString();
    }
    
    /**Rude and crude method to plot the percent hits of each motif to each file using data from motifHitSummary*/
    public String getMotifHitPlot(){
        StringBuffer data = new StringBuffer(
        "\n*************************************************************************************\n"+
        "Motif Hit Summary Plot: (%Seqs Above Cutoff to MemeLLPSPM and AddOneLLPSPM)\n"+
        "*************************************************************************************\n"+
        "Row # (see Motif Hit Summary)\n");
        for (int i=0; i<percents.length; i++){
            data.append((i+1)+"\t|");
            for (int j=0; j<percents[i]; j++) data.append("*");
            data.append("\n");
            data.append((++i+1)+"\t|");
            for (int j=0; j<percents[i]; j++) data.append("*");
            data.append("\n");
        }
        return data.toString();
    }
    
    //misc methods
    public void printSave(String newString){
        results.append(newString);
        System.out.print(newString);
    }
    public String toString(){
        return results.toString();
    }
    public void makeFileNameHash(){
        fileNameNumberHash = new HashMap();
        for (int i=0; i<memeParsers.length; i++){
            fileNameNumberHash.put(memeParsers[i].getParsedFileName(),new Integer(i+1));
        }
    }
    
    //getter methods
    public String getResults(){return results.toString();}
    public String getMemeReports(){return memeReports.toString();}
    public String getMotifSummaryText(){return motifSummary;}
    public String getMotifHitsSummaryText(){return motifHitsSummary;}
    public String getMotifHitPlotText(){return motifHitPlot;}
    
    //setter and appender methods
    public void setMemeParserArrayRef(MemeParser[] data){
        memeParsers = data;
    }
    public void setMotifHitPlotText(String data){
        motifHitPlot = data;
        printSave(data);
    }
    public void setMotifSummaryText(String data){
        motifSummary = data;
        printSave(data);
    }
    public void setMotifHitsSummaryText(String data){
        motifHitsSummary = data;
        printSave(data);
    }
    public void appendSelexOligoFiles(String data){
        selexOligoFiles.add(data);
        printSave(data);
    }
    public void setMemeCmdLnArgs(String data){
        memeCmdLnArgs = data;
    }
    public void setFileBaseName(String data){
        fileBaseName = data;
        printSave(data);
    }
    public void setResultsDirectory(String data){
        resultsDirectory = data;
        printSave(data);
    }
    public void appendMemeParserReport(String data){
        memeParserReports.append(data);
        printSave(data);
    }
    public void appendMemeReports(String data){
        memeReports.append(data);
        printSave(data);
    }
    public void appendMotifSearchResults(String data){
        motifSearchResults.append(data);
        printSave(data);
    }
    public void setMotifResults(ArrayList data){
        motifSearchResultObjs = data;
    }
}
