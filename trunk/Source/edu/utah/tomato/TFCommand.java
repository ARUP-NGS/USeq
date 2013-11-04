package edu.utah.tomato;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;


public abstract class TFCommand {
	//Type of command
	protected String commandString = null;
	protected String commandType = null;
	
	//Directory locations
	protected ArrayList<File> templateFiles = null;
	protected File rootDirectory = null;
	
	//Log file
	protected TFLogger logFile = null;

	//task list
	protected List<Callable<Boolean>> taskList = new ArrayList<Callable<Boolean>>();
	
	//cmd file options
	protected Integer wallTime = 0;
	protected String email = null;
	protected boolean suppress = false;
	protected boolean isFull = false;
	protected Integer jobs= null;
	
	//Tomato thread options
	protected Integer heartbeat = null;
	protected Integer failmax = null;
	
	//Thread daemon
	protected TFThreadDaemon daemon = null;

	//Variables
	protected HashMap<String,String> properties = null;
	
	public static TFCommand getCommandObject(File rootDirectory, String commandLine, TFLogger logFile, 
			String email, Integer wallTime, Integer heartbeat, Integer failmax, Integer jobs, boolean suppress,
			boolean deleteMetricsBams, boolean deleteReducedBams, boolean isFull, boolean use1KGenomes, String study, String splitType,  
			File targetFile, HashMap<String,String> properties) {
		File templateDir = new File(properties.get("TEMPLATE_PATH"));
		String[] commandParts = commandLine.split(":");
		if (commandParts.length < 2) {
			logFile.writeErrorMessage("Must be at least two parts for every command", true);
			System.exit(1);
		}
		ArrayList<File> templateFiles = new ArrayList<File>();
		File templateFile = new File(templateDir,"templates/" + commandParts[0] + "/" + commandParts[0] + "." + commandParts[1] + ".txt");
		if (!templateFile.exists()) {
			logFile.writeErrorMessage("Configuration file invalid, specified template file doesn not exist: " + commandLine,false);
			System.exit(1);
		}
		templateFiles.add(templateFile);
		File depFile = new File(templateDir,"templates/" + commandParts[0] + "/deps." + commandParts[1] + ".txt");
		if (depFile.exists()) {
			logFile.writeInfoMessage("Found dependencies: ");
			try {
				BufferedReader br = new BufferedReader(new FileReader(depFile));
				String line = "";
				while ((line = br.readLine()) != null) {
					File extraFile = new File(templateDir,"templates/" + commandParts[0] + "/" + line);
					if (!extraFile.exists()) {
						logFile.writeErrorMessage("Configuration file invalid, specified template file doesn not exist: " + extraFile.toString(),false);
						System.exit(1);
					}
					logFile.writeInfoMessage("\t" + extraFile.getName());
					templateFiles.add(extraFile);
				}
				br.close();
			} catch (FileNotFoundException fnfe) {
				logFile.writeErrorMessage("[getCommandObject] Count not find deps file", true);
			} catch (IOException ioex) {
				logFile.writeErrorMessage("[getCommandObject] Error reading deps file",true);
			}
		}
		
		TFCommand returnCommand = null;
		if (commandParts[0].equals(TFConstants.ANALYSIS_EXOME_ALIGN_NOVO) || commandParts[0].equals(TFConstants.ANALYSIS_EXOME_ALIGN_BWA)) {
			if (failmax == null) {
				failmax = 3;
			}
			returnCommand = new TFCommandExomeAlign(templateFiles,rootDirectory, commandLine, commandParts[0], logFile,email, wallTime, 
					heartbeat, failmax, jobs, suppress, isFull, properties);
		} else if (commandParts[0].equals(TFConstants.ANALYSIS_EXOME_METRICS)) {
			if (failmax == null) {
				failmax = 3;
			}
			returnCommand = new TFCommandExomeMetrics(templateFiles,rootDirectory, commandLine, commandParts[0], logFile,email, wallTime, 
					heartbeat, failmax, jobs, suppress, deleteMetricsBams, isFull, study, targetFile, properties);
		} else if (commandParts[0].equals(TFConstants.ANALYSIS_EXOME_VARIANT_RAW) || commandParts[0].equals(TFConstants.ANALYSIS_EXOME_VARIANT_VQSR)) {
			if (failmax == null) {
				failmax = 5;
			}
			returnCommand = new TFCommandExomeVariant(templateFiles,rootDirectory, commandLine, commandParts[0], logFile,email, wallTime, 
					heartbeat, failmax, jobs, suppress, deleteReducedBams, isFull, use1KGenomes, study, splitType, targetFile, properties);
		}
		
		else {
			logFile.writeErrorMessage("Command type not recognized: " + commandParts[0], true);
			System.exit(1);
		}
		
		logFile.writeInfoMessage(commandLine + " is allowed " + failmax + " failures");
		
		return returnCommand;
	}
	

	public TFCommand(ArrayList<File> templateFile, File rootDirectory, String commandString, String commandType, TFLogger logFile, 
			String email, Integer wallTime, Integer heartbeat, Integer failmax, Integer jobs, boolean suppress, boolean isFull, HashMap<String,String> properties) {
		this.templateFiles = templateFile;
		this.rootDirectory  = rootDirectory;
		this.commandString = commandString;
		this.commandType = commandType;
		this.logFile = logFile;
		this.wallTime = wallTime;
		this.email = email;
		this.heartbeat = heartbeat;
		this.failmax = failmax;
		this.jobs = jobs;
		this.suppress  = suppress;
		this.properties = properties;
		this.isFull = isFull;
	}
	
	
	/** Create file links needed based on the si object */
	public abstract void run(ArrayList<TFSampleInfo> sampleList);
	
    public String getCommandType() {
    	return this.commandType;
    }
    
    public void shutdown() {
    	if (this.daemon != null && this.daemon.isAlive()) {
    		logFile.writeWarningMessage("Recieved termination signal, interrupting daemon");
    		this.daemon.interrupt();
    		while(this.daemon.isAlive()){}
    	}
    }
    
    protected void sortVCF(File source, File dest) {
		try {
			if (!source.exists()) {
				logFile.writeErrorMessage("[sortVCF] Expected file does not exist: " + source.getAbsolutePath(),true);
				System.exit(1);
			}
			
			ProcessBuilder pb = new ProcessBuilder(properties.get("VCF_PATH_LOCAL") + "/vcf-sort",source.getAbsolutePath());
			
			Process p = pb.start();
			
			BufferedInputStream bis = new BufferedInputStream(p.getInputStream());
			BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dest));
			
			
			byte[] buffer = new byte[1024*1024*10];
			int n = -1;
			
			while((n = bis.read(buffer))!=-1) {
			  bos.write(buffer,0,n);
			}
		

			int val = p.waitFor();
			bos.close();
			bis.close();

			
			if (val != 0) {
				logFile.writeErrorMessage("[sortVcf] Error while sorting the VCF file: " + dest.getAbsolutePath(),true);
				BufferedReader br2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				String line2 = null;
				while((line2 = br2.readLine()) != null) {
					System.out.println(line2);
				}
				System.exit(1);
			}
			
		} catch (IOException ioex) {
			logFile.writeErrorMessage("[sortVcf] IO Exception while trying to move your file: " + source.getAbsolutePath(),true);
			System.exit(1);
		} catch (InterruptedException ieex) {
			logFile.writeErrorMessage("[sortVcf] Process was interrupted while trying to move your file: " + source.getAbsolutePath(),true);
			System.exit(1);
		}
	}
    
    protected void deleteFile(File source) {
    	try {
			if (!source.exists()) {
				logFile.writeErrorMessage("[deleteFile] Expected file does not exist: " + source.getAbsolutePath(),true);
				System.exit(1);
			}
			
			ProcessBuilder pb = new ProcessBuilder("rm",source.getAbsolutePath());
			Process p = pb.start();
			
			int val = p.waitFor();
			
			if (val != 0) {
				logFile.writeErrorMessage("[deleteFile] System could not delete your file " + source.getAbsolutePath(),true);
				System.exit(1);
			}
			
		} catch (IOException ioex) {
			logFile.writeErrorMessage("[deleteFile] IO Exception while trying to delete your file: " + source.getAbsolutePath(),true);
			System.exit(1);
		} catch (InterruptedException ieex) {
			logFile.writeErrorMessage("[deleteFile] Process was interrupted while trying to delete your file: " + source.getAbsolutePath(),true);
			System.exit(1);
		}
    }
    
    protected void moveFile(File source, File dest) {
		try {
			if (!source.exists()) {
				logFile.writeErrorMessage("[moveFile] Expected file does not exist: " + source.getAbsolutePath(),true);
				System.exit(1);
			}
			
			ProcessBuilder pb = new ProcessBuilder("mv",source.getAbsolutePath(),dest.getAbsolutePath());
			Process p = pb.start();
			
			int val = p.waitFor();
			
			if (val != 0) {
				logFile.writeErrorMessage("[moveFile] System could not move your file " + source.getAbsolutePath(),true);
				BufferedReader br2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				String line2 = null;
				while((line2 = br2.readLine()) != null) {
					System.out.println(line2);
				}
				System.exit(1);
			}
			
		} catch (IOException ioex) {
			logFile.writeErrorMessage("[moveFile] IO Exception while trying to move your file: " + source.getAbsolutePath(),true);
			System.exit(1);
		} catch (InterruptedException ieex) {
			logFile.writeErrorMessage("[moveFile] Process was interrupted while trying to move your file: " + source.getAbsolutePath(),true);
			System.exit(1);
		}
	}
    
    protected void cpFile(File source, File dest) {
		try {
			if (!source.exists()) {
				logFile.writeErrorMessage("[copyFile] Expected file does not exist: " + source.getAbsolutePath(),true);
				System.exit(1);
			}
			
			ProcessBuilder pb = new ProcessBuilder("cp","-r",source.getAbsolutePath(),dest.getAbsolutePath());
			Process p = pb.start();
			
			int val = p.waitFor();
			
			if (val != 0) {
				logFile.writeErrorMessage("[copyFile] System could not copy your file " + source.getAbsolutePath(),true);
				BufferedReader br2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				String line2 = null;
				while((line2 = br2.readLine()) != null) {
					System.out.println(line2);
				}
				System.exit(1);
			}
			
		} catch (IOException ioex) {
			logFile.writeErrorMessage("[copyFile] IO Exception while trying copy your file: " + source.getAbsolutePath(),true);
			System.exit(1);
		} catch (InterruptedException ieex) {
			logFile.writeErrorMessage("[copyFile] Process was interrupted while trying to copy your file: " + source.getAbsolutePath(),true);
			System.exit(1);
		}
	}
	
	protected void createLink(File filename, File linkname) {
		try {
			if (linkname.exists()) {
				if (linkname.getCanonicalFile().equals(filename.getAbsoluteFile())) {
					logFile.writeWarningMessage("[createLink] An existing link matches what you requested, using existing link");
				} else {
					logFile.writeErrorMessage("[createLink] An existing file exists at link location and it doesn't match request, refusing to overwrite existing file"
							+ "\nExisting: " + linkname.getCanonicalPath()
							+ "\nNew: " + filename.getAbsolutePath(),false);
					System.exit(1);
				}
			} else {
				
				ProcessBuilder pb = new ProcessBuilder("ln","-s",filename.getAbsolutePath(),linkname.getAbsolutePath());
				Process p = pb.start();
				
				int val = p.waitFor();
				
				if (val != 0) {
					logFile.writeErrorMessage("[createLink]  System could not create a link to you file " + filename.getAbsolutePath(),true);
					BufferedReader br2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
					String line2 = null;
					while((line2 = br2.readLine()) != null) {
						System.out.println(line2);
					}
					System.exit(1);
				}
				
			}
		} catch (IOException ioex) {
			logFile.writeErrorMessage("[createLink]  IO Exception while trying to create a link to your file: " + filename.getAbsolutePath(),true);
			System.exit(1);
		} catch (InterruptedException ieex) {
			logFile.writeErrorMessage("[createLink]  Process was interrupted while trying to create a link to your file: " + filename.getAbsolutePath(),true);
			System.exit(1);
		}
		
	}
	
	protected void mergeVcf(ArrayList<File> vcfList, File dest) {
		try {
			ArrayList<String> command  = new ArrayList<String>();
			command.add(properties.get("VCF_PATH_LOCAL") + "/vcf-concat");

			for (File bam: vcfList) {
				if (!bam.exists()) {
					logFile.writeErrorMessage("[mergeVcf] Expected file does not exist: " + bam.getAbsolutePath(),true);
					System.exit(1);
				}
				command.add(bam.getAbsolutePath());
			}
			
			
			ProcessBuilder pb = new ProcessBuilder(command);
			Process p = pb.start();
			
			BufferedInputStream bis = new BufferedInputStream(p.getInputStream());
			BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dest));
			
			
			byte[] buffer = new byte[1024*1024*10];
			int n = -1;
			
			while((n = bis.read(buffer))!=-1) {
			  bos.write(buffer,0,n);
			}
		

			int val = p.waitFor();
			bos.close();
			bis.close();

			
			if (val != 0) {
				logFile.writeErrorMessage("[mergeVcf] Error while merging the VCF files: " + dest.getAbsolutePath(),true);
				BufferedReader br2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				String line2 = null;
				while((line2 = br2.readLine()) != null) {
					System.out.println(line2);
				}
				System.exit(1);
			}
			
			
			
		} catch (IOException ioex) {
			logFile.writeErrorMessage("[mergeVcf] IO Exception while trying to merge the VCF files: " + dest.getAbsolutePath(),true);
			System.exit(1);
		} catch (InterruptedException ieex) {
			logFile.writeErrorMessage("[mergeVcf] Process was interrupted while trying to merge the VCF files: " + dest.getAbsolutePath(),true);
			System.exit(1);
		}
	}
	
	protected void bgzip(File source) {
		try {
			
			if (!source.exists()) {
				logFile.writeErrorMessage("[bgzip] Expected file does not exist: " + source.getAbsolutePath(),true);
			}
			
			ProcessBuilder pb = new ProcessBuilder(properties.get("TABIX_PATH_LOCAL") + "/bgzip",source.getAbsolutePath());
			Process p = pb.start();
			
			int val = p.waitFor();
			
			if (val != 0) {
				logFile.writeErrorMessage("[bgzip] Error while bgzipping the vcf: " + source.getAbsolutePath(),true);
				BufferedReader br2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				String line2 = null;
				while((line2 = br2.readLine()) != null) {
					System.out.println(line2);
				}
				System.exit(1);
			}
			
			
		} catch (IOException ioex) {
			logFile.writeErrorMessage("[bgzip] IO Exception while trying to bgzip the VCF files: " + source.getAbsolutePath(),true);
			System.exit(1);
		} catch (InterruptedException ieex) {
			logFile.writeErrorMessage("[bgzip] Process was interrupted while trying to bgzip the VCF files: " + source.getAbsolutePath(),true);
			System.exit(1);
		}
	}
	
	protected void tabix(File source) {
		try {
			if (!source.exists()) {
				logFile.writeErrorMessage("[tabix] Expected file does not exist: " + source.getAbsolutePath(),true);
			}
			
			ProcessBuilder pb = new ProcessBuilder(properties.get("TABIX_PATH_LOCAL") + "tabix","-p","vcf",source.getAbsolutePath());
			Process p = pb.start();
			
			int val = p.waitFor();
			
			if (val != 0) {
				logFile.writeErrorMessage("[tabix] Error while indexing the VCF files: " + source.getAbsolutePath(),true);
				BufferedReader br2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				String line2 = null;
				while((line2 = br2.readLine()) != null) {
					System.out.println(line2);
				}
				System.exit(1);
			}
			
		} catch (IOException ioex) {
			logFile.writeErrorMessage("[tabix] IO Exception while trying to index the VCF files: " + source.getAbsolutePath(),true);
			System.exit(1);
		} catch (InterruptedException ieex) {
			logFile.writeErrorMessage("[tabix] Process was interrupted while trying to indexthe VCF files: " + source.getAbsolutePath(),true);
			System.exit(1);
		}
	}
	
	
	protected void deleteFolder(File folder) {
	    File[] files = folder.listFiles();
	    if(files!=null) { //some JVMs return null for empty dirs
	        for(File f: files) {
	            if(f.isDirectory()) {
	                deleteFolder(f);
	            } else {
	                f.delete();
	            }
	        }
	    }
	    folder.delete();
	}
   
	
    protected void createCmd(HashMap<String,String> replacements, File cmdFile, int index) {
		//Write command file
		try {
			BufferedReader br = new BufferedReader(new FileReader(this.templateFiles.get(index)));
			BufferedWriter bw = new BufferedWriter(new FileWriter(cmdFile));
			
			
			if (this.suppress) {
				bw.write(String.format("#e %s -n\n", this.email));
			} else {
				bw.write(String.format("#e %s \n", this.email));
			}
			
			if (this.wallTime != null) {
				bw.write(String.format("#w %s\n", this.wallTime));
			}
			
			bw.write("\n\n");
		
			
			//Write command file
			String line = null;

			
			while((line = br.readLine()) != null) {
				String edited = line;
				for (String key: replacements.keySet()) {
				
					edited = edited.replaceAll(key, replacements.get(key));
				}
				bw.write(edited + "\n");
			}
			
			
			br.close();
			bw.close();
			
			
		} catch (IOException ioex) {
			logFile.writeErrorMessage("Error writing command file, exiting: " + ioex.getMessage(),true);
			System.exit(1);
		}	
		
	}
}