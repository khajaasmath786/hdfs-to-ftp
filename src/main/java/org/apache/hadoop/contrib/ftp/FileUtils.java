package org.apache.hadoop.contrib.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.ftp.FTPException;
import org.apache.hadoop.io.IOUtils;
import org.mortbay.log.Log;

/**
 * 文件操作工具类
 * @author 王厚达
 *
 */
public class FileUtils {

	/**
	 * 两个文件系统之间拷贝对象方法
	 * @throws ParseException
	 * @throws FTPClientException 
	 */
	public static boolean copy(FileSystem srcFS, Path src, Path dst, String queryStr, boolean deleteSource, boolean overwrite, Configuration conf) throws IOException, ParseException, FTPClientException {
		FileStatus fileStatus = srcFS.getFileStatus(src);
		// 获取ftpclient
		FTPClient ftpClient = FtpClientUtil.getFTPClient();
		boolean result = false;
		try {
			result = copy(srcFS, fileStatus, dst, queryStr, deleteSource, overwrite, conf, ftpClient);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				FtpClientUtil.releaseFtpClient();
			} catch (IOException ioe) {
				throw new FTPException("Failed to disconnect", ioe);
			}
		}
		return result;
	}
	
	
	/**
	 * @param srcFS
	 * @param src
	 * @param dst
	 * @param queryStr
	 * @param deleteSource
	 * @param overwrite
	 * @param conf
	 * @return Boolean
	 * @throws IOException
	 * @throws ParseException
	 * @throws FTPClientException 
	 */
	public static boolean copyAndRemove(FileSystem srcFS, Path src, Path dst, String queryStr, boolean deleteSource, boolean overwrite, Configuration conf) throws IOException, ParseException, FTPClientException {
		FileStatus fileStatus = srcFS.getFileStatus(src);
		// 获取ftpclient
		FTPClient ftpClient = FtpClientUtil.getFTPClient();
		boolean result = false;
		try {

			result = copy(srcFS, fileStatus, dst, queryStr, deleteSource, overwrite, conf, ftpClient);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				FtpClientUtil.releaseFtpClient();
			} catch (IOException ioe) {
				throw new FTPException("Failed to disconnect", ioe);
			}
		}
		return result;
	}

	/**
	 * Copy files between FileSystems.
	 * @throws ParseException
	 * @throws FTPClientException 
	 */
	public static boolean copy(FileSystem srcFS, FileStatus srcStatus, Path dst, String queryStr, boolean deleteSource, boolean overwrite, Configuration conf, FTPClient ftpClient) throws IOException,
			ParseException, FTPClientException {
		Path src = srcStatus.getPath();
		String dstPath = dst.toUri().getPath();
		if (srcStatus.isDirectory()) {// 若原路径是一个目录
			// 创建一个目录（如果乙存在默认不创建）
			ftpClient.makeDirectory(dstPath);
			FileStatus contents[] = srcFS.listStatus(src);
			long beginFilter=0;
			long endFileter=0;
			if (queryStr != null) {
				if(queryStr.startsWith("[")){
					beginFilter = System.currentTimeMillis();
					contents = getFilterContents(queryStr, contents);
					endFileter=System.currentTimeMillis();
				}else{
					beginFilter = System.currentTimeMillis();
					Long[] timeRange = parseTimeRange(queryStr);
					contents = getNewContents(timeRange, contents);
					endFileter=System.currentTimeMillis();
				}
				
			}
			Log.info("total file count:" + contents.length);
			Map<String, String> fileNameMap=null;
			long beginSkip=0;
			long endSkip=0;
			if(!overwrite){
				beginSkip = System.currentTimeMillis();
				fileNameMap= FtpClientUtil.getFileNameMap(dstPath);
				endSkip = System.currentTimeMillis();
			}
			int skiped=0;
			int transfered=0;
			for (int i = 0; i < contents.length; i++) {
				if (!overwrite && fileNameMap.containsKey(contents[i].getPath().getName())) {
					skiped++;
					Log.info("skiped filename:"+contents[i].getPath().getName());
					continue;
				}
				copy(srcFS, contents[i], new Path(dst, contents[i].getPath().getName()), queryStr, deleteSource, overwrite, conf, ftpClient);
				transfered++;
				if (transfered % 50 == 0 || i == contents.length - 1) {
					Log.info("have transfered " + transfered + " files");
				}
			}
			if(queryStr!=null){
				Log.info("filter time:" + (endFileter - beginFilter) + " ms");
			}
			if(!overwrite){
				Log.info("skip time:" + (endSkip - beginSkip) + " ms");
			}
			Log.info("total file count:" + contents.length);
			Log.info("total transtered: "+transfered);
			if(!overwrite){
				Log.info("total skiped files: "+skiped);
			}
			
		} else {// 若是文件，直接上传
			InputStream in = null;
			try {
				in = srcFS.open(src);
				FtpClientUtil.uploadToRemote(dstPath, in);
				Log.info("transfered filename :" + dst.getName());
			} catch (IOException e) {
				IOUtils.closeStream(in);
				throw e;
			}
		}
		if (deleteSource) {
			return srcFS.delete(src, true);
		} else {
			return true;
		}

	}

	/**
	 * 按文件名内容过滤
	 * @param queryStr
	 * @param contents
	 * @return FileStatus[] 
	 */
	public static FileStatus[] getFilterContents(String queryStr, FileStatus[] contents) {
		String reg=queryStr.substring(1, queryStr.length()-1);
		Pattern pattern = Pattern.compile(reg);
		List<FileStatus> statusList = new ArrayList<FileStatus>();
		for(FileStatus status:contents){
			if(!status.isDirectory()){
				String fileName=status.getPath().getName();
				Matcher matcher = pattern.matcher(fileName);
				if(matcher.matches()){
					statusList.add(status);
				}
			}
		}
		return statusList.toArray(new FileStatus[statusList.size()]);
	}
	/**
	 * filter files
	 * @param timeRange
	 * @param fileStatus
	 * @return FileStatus[]
	 */
	public static FileStatus[] getNewContents(Long[] timeRange, FileStatus[] fileStatus) {
		List<FileStatus> statusList = new ArrayList<FileStatus>();
		for (int i = 0; i < fileStatus.length; i++) {
			long modificationTime = fileStatus[i].getModificationTime();
			if (timeRange[1] != null) {
				if (timeRange[0] < modificationTime && modificationTime < timeRange[1]) {
					statusList.add(fileStatus[i]);
				}
			} else {
				if (timeRange[0] < modificationTime) {
					statusList.add(fileStatus[i]);
				}
			}
		}
		return statusList.toArray(new FileStatus[statusList.size()]);
	}

	/**
	 * parse queryParam to time range
	 * 
	 * @throws ParseException
	 **/
	public static Long[] parseTimeRange(String queryStr) throws ParseException {
		int length = queryStr.trim().length();
		SimpleDateFormat sdf = null;
		Long beginTime = null;
		Long endTime = null;

		if (length == ConfigUtils.FORMAT_DATE.length()) {
			sdf = new SimpleDateFormat(ConfigUtils.FORMAT_DATE);
			Date beginDate = sdf.parse(queryStr);
			beginTime = beginDate.getTime();
			Date endDate = DateUtils.addDays(beginDate, 1);
			endTime = endDate.getTime();
		} else if (length == ConfigUtils.FORMAT_HOUR.length()) {
			sdf = new SimpleDateFormat(ConfigUtils.FORMAT_HOUR);
			Date beginDate = sdf.parse(queryStr);
			beginTime = beginDate.getTime();
			Date endDate = DateUtils.addHours(beginDate, 1);
			endTime = endDate.getTime();

		} else if (length == ConfigUtils.FORMAT_SS.length() + 1) {
			sdf = new SimpleDateFormat(ConfigUtils.FORMAT_SS);
			Date beginDate = sdf.parse(queryStr);
			beginTime = beginDate.getTime();
		} else {
			throw new RuntimeException("输入的时间参数不合法!your value is :" + queryStr);
		}

		Long[] timeRange = { beginTime, endTime };
		return timeRange;
	}

	/**
	 * @Title: quicksort
	 * @Description: quick sort by access time
	 */
	static FileStatus[] quicksort(FileStatus n[], int left, int right) {
		int dp;
		if (left < right) {
			dp = partition(n, left, right);
			quicksort(n, left, dp - 1);
			quicksort(n, dp + 1, right);
		}
		return n;
	}

	/**
	 * 获取分区方法
	 * @param content
	 * @param left
	 * @param right
	 * @return int
	 */
	static int partition(FileStatus content[], int left, int right) {
		FileStatus pivot = content[left];
		while (left < right) {
			while (left < right && content[right].getAccessTime() >= pivot.getAccessTime())
				right--;
			if (left < right)
				content[left++] = content[right];
			while (left < right && content[left].getAccessTime() <= pivot.getAccessTime())
				left++;
			if (left < right)
				content[right--] = content[left];
		}
		content[left] = pivot;
		return left;
	}


}
