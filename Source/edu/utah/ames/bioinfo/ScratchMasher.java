package edu.utah.ames.bioinfo;

import java.io.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import edu.utah.ames.bioinfo.*;

import util.gen.Misc;

/**
 * This class identifies users in the /scratch/ directory, creates a User object for
 * each, and then for each user, finds and stores their associated email
 * addresses to be used for notification of impending file deletion. Files are grouped into
 * either warnFiles or deleteFiles based on size and lastModifiedDate. Soft linked files are
 * skipped. Users with warnFiles and/or deleteFiles are emailed a list of offending files.
 * 
 * @author darren.ames@hci.utah.edu
 */

public class ScratchMasher {

	//fields
	private static String path = "/scratch/";
	//regex to find email addresses
	static String REGEX = "^#e\\s+(.+@.+)";
	static HashMap<File, Long> fileDates = null;
	//set warn cutoff time: 14 days ago
	private static long warnDate = System.currentTimeMillis()
			- (14L * 24 * 60 * 60 * 1000);
	//set delete cutoff time: 16 days ago
	private static long deleteDate = System.currentTimeMillis()
			- (16L * 24 * 60 * 60 * 1000);
	//filesize cutoff: 50Mb
	private static long fileSize = 52428800;
	private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd");
	//date file deletion begins
	Date startDate = sdf.parse("2013/07/27");
	Date todayDate = new Date();
	//create logger
	//private Logger logFile = new Logger(new File("/home/u0785353/UtilLogs/ScratchMasher/logs/"), "log", "INFO");

	//constructor
	public ScratchMasher (String[] args) throws ParseException {

		processArgs(args);
		File tomatoDir = new File(path);

		//make serialized file with file objects and their last modified dates
		File savedFileDates = new File(tomatoDir, ".savedFileDates.ser");

		//fetch file dates and save in serialized file
		fileDates = fetchFileDates(savedFileDates);

		//create File object 
		File[] dirs = tomatoDir.listFiles();
		ArrayList<User> users = fetchUsers(dirs);

		for (User user : users) {

			//write to log file
			//logFile.writeInfoMessage("Running ScratchMasher");
			//logFile.writeInfoMessage("Working in directory: " + user.getDirectory());

			//find email addresses for each user
			boolean found = fetchEmailAddress(user);

			//skip folders missing .email.txt file
			if (!found) {
				continue;
			}

			//start walking
			walk(user);

			//send some emails!
			sendMail(user);
		}
		Stuff.saveObject(savedFileDates, fileDates);

		//write info to log and close it
		//logFile.writeInfoMessage("Finished running ScratchMashser");
		//logFile.closeLogger();
	}

	/**
	 * Descends through files in each user's job directory.
	 * @param user
	 * @throws IOException
	 */
	public void walk(User user) {

		//make arraylist of files
		ArrayList<File> alFiles = fetchAllFilesRecursively(user.getDirectory());

		//for each user's files...
		for (File f : alFiles) {
			checkFile(user, f);
		}
	}

	/**
	 * Fetches files that don't start with a '.' from a directory recursing through sub directories
	 * @param directory
	 * @return
	 */
	public static ArrayList<File> fetchAllFilesRecursively (File directory){
		ArrayList<File> files = new ArrayList<File>(); 
		File[] list = directory.listFiles();
		if (list != null){
			for (int i = 0; i < list.length; i++){
				if (list[i].isDirectory()) {
					ArrayList<File> al = fetchAllFilesRecursively (list[i]);
					int size = al.size();
					for (int x = 0; x < size; x++){
						File test = al.get(x);
						if (test.getName().startsWith(".") == false) files.add(test);
					}
				}
				else if (list[i].getName().startsWith(".") == false) files.add(list[i]);				
			}
		}
		return files;
	}

	/**
	 * Tests if a file is a symbolic (soft) link
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public boolean isSymlink(File file) throws IOException {
		if (file == null) {
			throw new NullPointerException("File must not be null");
		}
		File canon;
		if (file.getParent() == null) {
			canon = file;
		}
		else {
			File canonDir = file.getParentFile().getCanonicalFile();
			canon = new File(canonDir, file.getName());
		}
		return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
	}

	/**
	 * Compares file last modified dates to see if action is necessary.
	 * @param user
	 * @param fileObject
	 * @throws IOException
	 */
	public void checkFile(User user, File fileObject) {

		try {
			//check to make sure file is not a soft-link, otherwise it follows the path regardless
			if (!this.isSymlink(fileObject)) {
				//is the file too big?
				if (fileObject.getCanonicalFile().length() > fileSize) {
					//Long fileDate = fileDates.get(f);
					Long fileDate = Long.valueOf(fileObject.lastModified());

					//check for fileDate, if null, set to current time
					if (fileDate == null) {
						fileDate = System.currentTimeMillis();
						fileDates.put(fileObject, fileDate);
					} else {
						//warn cutoff? >=14 days ago && < 16 days ago
						if ((fileDate.longValue() <= warnDate)
								&& (fileDate.longValue() > deleteDate)) {

							//add fileObject to warnFiles ArrayList
							user.getWarnFiles().add(fileObject);

							//log it
							//logFile.writeInfoMessage("Found these warn files: " + fileObject.getPath());
						}
						//delete cutoff >=16 days ago
						else if (fileDate.longValue() <= deleteDate) {

							//add fileObject to deleteFiles ArrayList
							user.getDeleteFiles().add(fileObject);

							//log it
							//logFile.writeInfoMessage("Will delete the following files: " + fileObject.getPath());

							//****uncomment this to start deleting offending files****
							//fileObject.deleteOnExit();
						}
					}
				}
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Lists directory names, which will be used as user names.
	 * @param files
	 * @return
	 */
	public ArrayList<User> fetchUsers(File[] files) {

		//create ArrayList to hold User names
		ArrayList<User> userNames = new ArrayList<User>();

		//create User object to hold User names
		for (File file : files) {
			if (file.isDirectory()) {
				User user = new User(file);

				//add user names to user objects
				userNames.add(user);
			}
		}
		return userNames;
	}

	/**
	 * Grabs email addresses from cmd.txt files found in user directories.
	 * @param user
	 * @throws IOException
	 */
	public boolean fetchEmailAddress(User user) {

		try {
			//call pattern to find valid email addresses within a file
			Pattern pattern = Pattern.compile(REGEX, Pattern.CASE_INSENSITIVE);

			//find the .email.txt file
			FileFind2 ff2 = new FileFind2(user.getDirectory(), user);
			ff2.find();

			//read in .email.txt file
			user.getCmdFiles().get(0);

			//check for missing .email.txt file
			if (user.getCmdFiles().isEmpty()) {
				return false;
			}

			for (File c : user.getCmdFiles()) {
				BufferedReader br = new BufferedReader(new FileReader(c));

				//search for email address string in file
				for (String line = br.readLine(); line != null; line = br
						.readLine()) {
					Matcher m = pattern.matcher(line);

					if (m.find()) {

						//set email address in User HashSet
						user.getEmail().add(m.group(1));
					}
				}
				br.close();
			}
			//write to log
			for (String emailString : user.getEmail()) {
				//logFile.writeInfoMessage(String.format("Found email %s for user %s", emailString, user.getName()));
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		return true;
	}

	/**
	 * Grabs file modified dates from each user's directories and saves in a serialized File object in their directory.
	 * @param serFile
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public HashMap<File, Long> fetchFileDates(File serFile) {

		if (serFile.canRead() == false || !serFile.exists()) {
			return new HashMap<File, Long>();
		}
		return (HashMap<File, Long>) Stuff.fetchObject(serFile);
	}

	/**
	 * method to compose email body
	 * @param msg
	 * @param user
	 * @return
	 * @throws IOException
	 * @throws ParseException 
	 */
	public String getMessage(String msg, User user) throws IOException, ParseException {

		//instantiate new StringBuffer object for holding email message body
		StringBuffer message = new StringBuffer();
		message.append("Hello,"
				+ "\n\n"
				+ "Our system indicates that you have old data files in /scratch/ that are scheduled for deletion. "
				+ "\nUsage policy states that this volume is for temporary usage, and anything over 2 weeks old can and"
				+ "\nwill be deleted. We ask that you move these files to another volume or delete them before they are"
				+ "\nremoved. File details are below. "
				+ "\n\nIf you just need a little extra time, you can use the touch -m command to modify the mtime of your"
				+ "\n files [http://en.wikipedia.org/wiki/Touch_(Unix)], but please do not abuse this. We actively monitor"
				+ "\nusage of this space."
				+ "\n\nPlease contact a member of the Bioinformatics Core if you believe this message was received in error,"
				+ "\nor if your project requires greater temporary space allocation."
				+ "\n\nThank you," + "\n\nU of U Bioinformatics Core");
		/**
		//first ScratchMasher run
		if (todayDate.before(startDate)) {
			message.append("\n\n\nThe following files will be deleted on Monday July 12, 2013:\n");
			//check if warnFiles are deleteFiles ArrayList are empty
			if (user.getWarnFiles().isEmpty() == true && user.getDeleteFiles().isEmpty() == true) {
				message.append("\nNone\n");
			}
			else {
				int counter = 0;
				//for each user with offending files in the warnFiles ArrayList
				for (int i = 0; i < user.getWarnFiles().size(); i++) {

					//get files in warnFile ArrayList
					File warnFile = user.getWarnFiles().get(i);

					//format lastModifiedDate so it's readable
					long format = warnFile.lastModified();
					DateFormat sdf = SimpleDateFormat.getInstance();
					message.append("\n" + (++counter) + ".\t" + "Name: " + warnFile.getCanonicalPath() + 
							"\n" + "\tSize: " + (warnFile.length()/1000000) + "MB\n" + 
							"\tLast modified: " + sdf.format(format) + "\n");
				}
				//for each user with offending files in the deleteFiles ArrayList
				for (int i = 0; i < user.getDeleteFiles().size(); i++) {

					//get files in the deleteFile ArrayList
					File deleteFile = user.getDeleteFiles().get(i);
					//format lastModifiedDate so it's readable
					long format = deleteFile.lastModified();
					DateFormat sdf = SimpleDateFormat.getInstance();
					message.append("\n" + (++counter) + ".\t" + "Name: " + deleteFile.getCanonicalPath() + 
							"\n" + "\tSize: " + (deleteFile.length()/1000000) + "MB\n" + 
							"\tLast modified: " + sdf.format(format) + "\n");
				}
			}
		}
		//every other ScratchMasher run after the first one
		else {
		 **/
		int counter = 0;
		message.append("\n\n\nThe following files will be deleted in 2 days:\n");
		if (user.getWarnFiles().isEmpty() == true) {
			message.append("\nNone\n");
		}
		else {
			//for each user with offending files in the warnFiles ArrayList
			for (int i = 0; i < user.getWarnFiles().size(); i++) {

				//get files in warnFile ArrayList
				File warnFile = user.getWarnFiles().get(i);

				//format lastModifiedDate so it's readable
				long format = warnFile.lastModified();
				DateFormat sdf = SimpleDateFormat.getInstance();
				message.append("\n" + (++counter) + ".\t" + "Name: " + warnFile.getCanonicalPath() + 
						"\n" + "\tSize: " + (warnFile.length()/1000000) + "MB\n" + 
						"\tLast modified: " + sdf.format(format) + "\n\n");
			}
		}
		//print the list of deleted files
		message.append("\nDeleted files:\n");

		//check if deleteFiles ArrayList is empty
		if (user.getDeleteFiles().isEmpty() == true) {
			message.append("\nNone\n");
		}
		else {
			//for each user with offending files in the deleteFiles ArrayList
			for (int i = 0; i < user.getDeleteFiles().size(); i++) {

				//get files in the deleteFile ArrayList
				File deleteFile = user.getDeleteFiles().get(i);
				//format lastModifiedDate so it's readable
				long format = deleteFile.lastModified();
				DateFormat sdf = SimpleDateFormat.getInstance();
				message.append("\n" + (++counter) + ".\t" + "Name: " + deleteFile.getCanonicalPath() + 
						"\n" + "\tSize: " + (deleteFile.length()/1000000) + "MB\n" + 
						"\tLast modified: " + sdf.format(format) + "\n");
			}
		}
		return message.toString();
	}

	/**
	 * Emailing method that configures mailing params
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
		//props.put("mail.smtp.auth", "true");
		//props.put("mail.smtp.starttls.enable","true");

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
	 * Magic. Do not touch.
	 * @param a
	 * @param separator
	 * @return
	 */
	public String arrayToString(String[] a, String separator) {
		if (a == null || separator == null) {
			return null;
		}
		StringBuilder result = new StringBuilder();
		if (a.length > 0) {
			result.append(a[0]);
			for (int i = 1; i < a.length; i++) {
				result.append(separator);
				result.append(a[i]);
			}
		}
		return result.toString();
	}

	/**
	 * Method to send mail to users if they have warnFiles or deleteFiles.
	 * @param user
	 * @throws ParseException 
	 */
	public void sendMail(User user) throws ParseException {
		String msg = null;

		//determine if there are files in the delete/warn files ArrayLists
		if (user.getDeleteFiles().isEmpty() != true || user.getWarnFiles().isEmpty() != true) {
			String[] emails = new String[user.getEmail().size()];

			int i = 0;
			for (String e : user.getEmail()) {
				emails[i] = e;
				i++;
			}
			try {
				//try sending email to user with list of warn/delete files
				this.postMail(this.arrayToString(emails, ","), "Scratch directory cleanup", this.getMessage(msg, user), "doNotReply@hci.utah.edu");
				
				//log it
				//logFile.writeInfoMessage("Sending email notification to: " + this.arrayToString(emails, ","));
			} catch (MessagingException e1) {
				e1.printStackTrace();
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
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
					case 'p': path = new String(args[++i]); break;
					case 's': fileSize = Long.parseLong(args[++i]); break; //file size cutoff
					case 'd': deleteDate = Long.parseLong(args[++i]); break;	//delete date cutoff
					case 'w': warnDate = Long.parseLong(args[++i]); break;	//warn date cutoff
					default: Misc.printErrAndExit("\nProblem--unknown option used!" + mat.group());
					}
				}
				catch (Exception e) {
					Misc.printErrAndExit("\nSorry, something doesn't look right with this parameter: -" + test + "\n");
				}
			}
		}
	}

	/**
	 * @param args
	 * @throws IOException
	 * @throws MessagingException
	 */
	public static void main(String[] args) throws IOException, MessagingException {

		if (args.length == 0) {
			printDocs();
			System.exit(0);
		}
		try {
			new ScratchMasher(args);
		} catch (ParseException e) {
			e.printStackTrace();
		}
	}

	public static void printDocs() {
		System.out.println("\n" +
				"**********************************************************************************\n" +
				"**                           ScratchMasher: July 2013                           **\n" +
				"**********************************************************************************\n" +
				"ScratchMasher identifies users in the /scratch volume, creates a User object for \n" +
				"each, and then for each user, finds and stores their associated email addresses to \n" +
				"be used for notification of impending file deletion. Files are grouped into either \n" +
				"warn files (to be deleted in 2 days) or delete files (passing delete criteria and \n" +
				"removed from file system). Current criteria based upon file size (>50MB by default) \n" +
				"and file last modified date. Users are sent an email with a list of warn files or \n" +
				"those that have been deleted.\n" +

				"\nOptions:\n" +
				"-p Full path for directory where ScratchMasher will start walking.\n" +
				"   Default: /scratch/\n" +
				"-d Delete date in number of days (int) old relative to current system time.\n" +
				"	Default: 16 days ago.\n"+
				"-w Warn date in number of days (int) old relative to current system time.\n" +
				"	Default: 14 days ago.\n"+
				"-s Minimum file size cutoff for identifying big files.\n"+
				"	Default: 52428800 bytes (50 MB)\n" +

				"\nExample: java pathToUSeq/Apps/ScratchMasher -p /scratch/\n\n" +
				"Questions or comments about this app? Contact: darren.ames@hci.utah.edu\n\n" +
				"**************************************************************************************\n");
	}
}