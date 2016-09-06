package edu.umich.its.cpm;

import org.apache.http.HttpResponse;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.Metadata;
import com.box.sdk.ProgressListener;

@Service
@Component
public class MigrationTaskService {
	
	@Autowired
	BoxAuthUserRepository uRepository;
	
	@Autowired
	MigrationBoxFileRepository fRepository;
	
	@Autowired
	MigrationEmailMessageRepository mRepository;
	
	@Autowired
	private Environment env;
	
	// String values used in content json feed
	private static final String CTOOLS_ACCESS_STRING = "/access/content";
	private static final String CTOOLS_CITATION_ACCESS_STRING = "/access/citation/content";
	private static final String CTOOLS_CONTENT_STRING = "/content";
	private static final String CONTENT_JSON_ATTR_CONTENT_COLLECTION = "content_collection";
	private static final String CONTENT_JSON_ATTR_CONTAINER = "container";
	private static final String CONTENT_JSON_ATTR_TITLE = "title";
	private static final String CONTENT_JSON_ATTR_TYPE = "type";
	private static final String CONTENT_JSON_ATTR_URL = "url";
	private static final String CONTENT_JSON_ATTR_DESCRIPTION = "description";
	private static final String CONTENT_JSON_ATTR_AUTHOR = "author";
	private static final String CONTENT_JSON_ATTR_COPYRIGHT_ALERT = "copyrightAlert";
	private static final String CONTENT_JSON_ATTR_WEB_LINK_URL = "webLinkUrl";
	private static final String CONTENT_JSON_ATTR_SIZE = "size";

	// true value used in entity feed
	private static final String BOOLEAN_TRUE = "true";

	// integer value of stream operation buffer size
	private static final int STREAM_BUFFER_CHAR_SIZE = 1024;

	// Box has a hard limit of 5GB per any single file
	// use the decimal version of GB here, smaller than the binary version
	private static final long MAX_CONTENT_SIZE_FOR_BOX = 5L * 1024 * 1024 * 1024;

	private static final Logger log = LoggerFactory
			.getLogger(MigrationTaskService.class);

	/**
	 * Download CTools site resource in zip file
	 * 
	 * @return status of download
	 */
	public void downloadZippedFile(Environment env, HttpServletRequest request,
			HttpServletResponse response, String userId,
			HashMap<String, Object> sessionAttributes, String site_id,
			String migrationId, MigrationRepository repository) {
		// hold download status
		StringBuffer downloadStatus = new StringBuffer();
		List<MigrationFileItem> itemStatus = new ArrayList<MigrationFileItem>();

		// login to CTools and get sessionId
		if (sessionAttributes.containsKey(Utils.SESSION_ID)) {
			String sessionId = (String) sessionAttributes.get(Utils.SESSION_ID);
			HttpContext httpContext = (HttpContext) sessionAttributes
					.get("httpContext");

			// 3. get all sites that user have permission site.upd
			RestTemplate restTemplate = new RestTemplate();
			// the url should be in the format of
			// "https://server/direct/site/SITE_ID.json"
			String requestUrl = env.getProperty(Utils.ENV_PROPERTY_CTOOLS_SERVER_URL)
					+ "direct/content/site/" + site_id + ".json?_sessionId="
					+ sessionId;
			String siteResourceJson = null;
			try {
				siteResourceJson = restTemplate.getForObject(requestUrl,
						String.class);

				// null zip content
				byte[] zipContent = null;

				log.info(":downloadZippedFile begin: start downloading content zip file for site "
						+ site_id);
				//
				// Sends the response back to the user / browser. The
				// content for zip file type is "application/zip". We
				// also set the content disposition as attachment for
				// the browser to show a dialog that will let user
				// choose what action will he do to the sent content.
				//
				response.setContentType(Utils.MIME_TYPE_ZIP);
				String zipFileName = site_id + "_content.zip";
				response.setHeader("Content-Disposition",
						"attachment;filename=\"" + zipFileName + "\"");

				ZipOutputStream out = new ZipOutputStream(
						response.getOutputStream());
				out.setLevel(9);

				// prepare zip entry for site content objects
				itemStatus = zipSiteContent(httpContext, siteResourceJson,
						sessionId, out);

				out.flush();
				out.close();
				log.info("Finished zip file download for site " + site_id);

			} catch (RestClientException e) {
				String errorMessage = "Cannot find site by siteId: " + site_id
						+ " " + e.getMessage();
				Response.status(Response.Status.NOT_FOUND).entity(errorMessage)
						.type(MediaType.TEXT_PLAIN).build();
				log.error(errorMessage);
				downloadStatus.append(errorMessage);
			} catch (IOException e) {
				String errorMessage = "Problem getting content zip file for "
						+ site_id + " " + e.getMessage();
				Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorMessage).type(MediaType.TEXT_PLAIN)
						.build();
				log.error(errorMessage);
				downloadStatus.append(errorMessage);

			} catch (Exception e) {
				String errorMessage = "Migration status for " + site_id + " "
						+ e.getClass().getName();
				Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorMessage).type(MediaType.TEXT_PLAIN)
						.build();
				log.error("downloadZippedFile ", e);
				downloadStatus.append(errorMessage + Utils.LINE_BREAK);
			}
		} else {
			String userError = "Cannot become user " + userId;
			log.error(userError);
			downloadStatus.append(userError);
		}

		// the HashMap object holds itemized status information
		HashMap<String, Object> statusMap = new HashMap<String, Object>();
		statusMap.put(Utils.MIGRATION_STATUS, downloadStatus);
		statusMap.put(Utils.MIGRATION_DATA, itemStatus);

		// update the status and end_time of migration record
		setMigrationEndTimeAndStatus(migrationId, repository, new JSONObject(statusMap));

		return;

	}

	/**
	 * update the status and end_time of migration record
	 * @param migrationId
	 * @param repository
	 * @param obj
	 */
	public void setMigrationEndTimeAndStatus(String migrationId,
			MigrationRepository repository, JSONObject obj) {
		repository.setMigrationEndTime(
						new java.sql.Timestamp(System.currentTimeMillis()),
						migrationId);
		repository.setMigrationStatus(obj.toString(), migrationId);
	}

	/**
	 * create zip entry for folders and files
	 */
	private List<MigrationFileItem> zipSiteContent(HttpContext httpContext,
			String siteResourceJson, String sessionId, ZipOutputStream out) {
		// the return list of MigrationFileItem objects, with migration status
		// recorded
		List<MigrationFileItem> fileItems = new ArrayList<MigrationFileItem>();

		// site root folder
		String rootFolderPath = null;

		JSONObject obj = new JSONObject(siteResourceJson);

		JSONArray array = obj
				.getJSONArray(CONTENT_JSON_ATTR_CONTENT_COLLECTION);

		// the map stores folder name conversions;
		// folder name can be changed within CTools:
		// it can be named differently, while the old name still uses in the
		// folder/resource ids
		HashMap<String, String> folderNameMap = new HashMap<String, String>();
		for (int i = 0; i < array.length(); i++) {

			// item status information
			StringBuffer itemStatus = new StringBuffer();

			JSONObject contentItem = array.getJSONObject(i);

			String type = Utils.getJSONString(contentItem,
					CONTENT_JSON_ATTR_TYPE);
			String title = Utils.getJSONString(contentItem,
					CONTENT_JSON_ATTR_TITLE);
			String copyrightAlert = Utils.getJSONString(contentItem,
					CONTENT_JSON_ATTR_COPYRIGHT_ALERT);

			String contentAccessUrl = contentItem
					.getString(CONTENT_JSON_ATTR_URL);

			// get only the url after "/access/" string
			String contentUrl = getContentUrl(contentAccessUrl);
			if (contentUrl == null) {
				// log error
				itemStatus.append("Content url " + contentUrl
						+ " does not contain " + CTOOLS_ACCESS_STRING + " nor "
						+ CTOOLS_CITATION_ACCESS_STRING);

				// document the error and break
				MigrationFileItem item = new MigrationFileItem(contentUrl,
						title, itemStatus.toString());
				fileItems.add(item);
				break;
			}

			// modify the contentAccessUrl if needed for copyright alert setting
			// always export the resource content regardless of the copyright
			// settings
			contentAccessUrl = Utils.getCopyrightAcceptUrl(copyrightAlert,
					contentAccessUrl);

			// get container string from content url
			String container = getContainerStringFromContentUrl(contentUrl);

			// come checkpoints before migration
			itemStatus = preMigrationChecks(itemStatus, contentUrl, container,
					title);

			if (itemStatus.length() == 0) {
				// no errors, proceed with migration
				if (Utils.COLLECTION_TYPE.equals(type)) {
					// folders
					if (rootFolderPath == null) {
						rootFolderPath = contentUrl;
					} else {
						// create the zipentry for the sub-folder first
						String folderName = contentUrl.replace(rootFolderPath,
								"");
						// update folder name
						folderNameMap = Utils.updateFolderNameMap(
								folderNameMap, title, folderName);
						if (folderNameMap.containsKey(folderName)) {
							// if the folder name have / in it then we are not zipping the file with original name instead the folder
							// name will contain _ in it. As having the / will have cause the zip library creating inner folders
							if (!(StringUtils.countOccurrencesOf(folderNameMap.get(folderName), "/") > 1)) {
								folderName = folderNameMap.get(folderName);
							}
						}

						log.info("download folder " + folderName);

						ZipEntry folderEntry = new ZipEntry(folderName);
						try {
							out.putNextEntry(folderEntry);
						} catch (IOException e) {
							String ioError = "zipSiteContent: problem closing zip entry "
									+ folderName + " " + e;
							log.error(ioError);
							itemStatus.append(ioError + Utils.LINE_BREAK);
						}
					}

				} else {
					// get the zip file name with folder path info
					String zipFileName = container.substring(container
							.indexOf(rootFolderPath) + rootFolderPath.length());
					zipFileName = zipFileName.concat(Utils.sanitizeName(type,
							title));
					log.info("zip download processing file " + zipFileName);

					// value not null for WebLink content item
					String webLinkUrl = Utils.getJSONString(contentItem,
							CONTENT_JSON_ATTR_WEB_LINK_URL);

					// Call the zipFiles method for creating a zip stream.
					String zipFileStatus = zipFiles(type, httpContext,
							zipFileName, title, webLinkUrl, contentAccessUrl,
							sessionId, out, folderNameMap);
					itemStatus.append(zipFileStatus + Utils.LINE_BREAK);
				}
			}
			MigrationFileItem fileItem = new MigrationFileItem(contentUrl,
					title, itemStatus.toString());

			fileItems.add(fileItem);
		} // for
		return fileItems;
	}

	/**
	 * based on the content url, get its parent folder - container folder url
	 * ends with "/", need remove the ending "/" first;
	 * <container_path_end_with_slash><content_title>
	 * 
	 * @param contentUrl
	 * @return
	 */
	private String getContainerStringFromContentUrl(String contentUrl) {
		String container = contentUrl.endsWith(Utils.PATH_SEPARATOR) ? contentUrl
				.substring(0, contentUrl.length() - 1) : contentUrl;
		container = container.substring(0,
				container.lastIndexOf(Utils.PATH_SEPARATOR) + 1);
		return container;
	}

	/**
	 * create zip entry for files
	 */
	private String zipFiles(String type, HttpContext httpContext,
			String fileName, String title, String webLinkUrl,
			String fileAccessUrl, String sessionId, ZipOutputStream out,
			HashMap<String, String> folderNameUpdates) {
		log.info("*** " + fileAccessUrl);

		// record zip status
		StringBuffer zipFileStatus = new StringBuffer();

		// create httpclient
		HttpClient httpClient = HttpClientBuilder.create().build();
		InputStream content = null;
		try {
			// get file content from /access url
			HttpGet getRequest = new HttpGet(fileAccessUrl);
			getRequest.setHeader("Content-Type",
					"application/x-www-form-urlencoded");
			HttpResponse r = httpClient.execute(getRequest, httpContext);
			content = r.getEntity().getContent();
		} catch (Exception e) {
			log.info(e.getMessage());
		}

		// exit if content stream is null
		if (content == null)
			return null;

		try {
			int length = 0;
			byte data[] = new byte[STREAM_BUFFER_CHAR_SIZE];
			BufferedInputStream bContent = null;

			try {
				bContent = new BufferedInputStream(content);

				// checks for folder renames
				fileName = Utils.updateFolderPathForFileName(fileName,
						folderNameUpdates);

				log.info("download file " + fileName);

				if (Utils.isOfURLMIMEType(type)) {
					try {
						// get the html file content first
						String webLinkContent = Utils.getWebLinkContent(title,
								webLinkUrl);

						ZipEntry fileEntry = new ZipEntry(fileName);
						out.putNextEntry(fileEntry);
						out.write(webLinkContent.getBytes());
					} catch (java.net.MalformedURLException e) {
						// return status with error message
						zipFileStatus
								.append(e.getMessage() + "Link "
										+ title
										+ " could not be migrated. Please change the link name to be the complete URL and migrate the site again.");
					} catch (IOException e) {
						// return status with error message
						zipFileStatus
								.append(e.getMessage() + "Link "
										+ title
										+ " could not be migrated. Please change the link name to be the complete URL and migrate the site again.");
					}
				} else {

					ZipEntry fileEntry = new ZipEntry(fileName);
					out.putNextEntry(fileEntry);
					int bCount = -1;

					bContent = new BufferedInputStream(content);
					while ((bCount = bContent.read(data)) != -1) {
						out.write(data, 0, bCount);
						length = length + bCount;
					}
				}
				out.flush();

				try {
					out.closeEntry(); // The zip entry need to be closed
				} catch (IOException ioException) {
					String ioExceptionString = "zipFiles: problem closing zip entry "
							+ fileName + " " + ioException;
					log.error(ioExceptionString);
					zipFileStatus.append(ioExceptionString + Utils.LINE_BREAK);
				}
			} catch (IllegalArgumentException iException) {
				String IAExceptionString = "zipFiles: problem creating BufferedInputStream with content and length "
						+ data.length + iException;
				log.warn(IAExceptionString);
				zipFileStatus.append(IAExceptionString + Utils.LINE_BREAK);
			} finally {
				if (bContent != null) {
					try {
						bContent.close(); // The BufferedInputStream needs to be
											// closed
					} catch (IOException ioException) {
						String ioExceptionString = "zipFiles: problem closing FileChannel "
								+ ioException;
						log.warn(ioExceptionString);
						zipFileStatus.append(ioExceptionString + Utils.LINE_BREAK);
					}
				}
			}
		} catch (IOException e) {
			String ioExceptionString = " zipFiles--IOException: : fileName="
					+ fileName;
			log.warn(ioExceptionString);
			zipFileStatus.append(ioExceptionString + Utils.LINE_BREAK);
		} finally {
			if (content != null) {
				try {
					content.close(); // The input stream needs to be closed
				} catch (IOException ioException) {
					String ioExceptionString = "zipFiles: problem closing Inputstream content for"
							+ fileName + ioException;
					log.warn(ioExceptionString);
					zipFileStatus.append(ioExceptionString + Utils.LINE_BREAK);
				}
			}
			try {
				out.flush();
			} catch (Exception e) {
				log.warn(this + " zipFiles: exception " + e.getMessage());
			}
		}

		// return success message
		if (zipFileStatus.length() == 0) {
			zipFileStatus.append(fileName
					+ " was added into zip file successfully.");
		}

		return zipFileStatus.toString();
	}

	/*************** Box Migration ********************/
	@Async
	private Future<HashMap<String, String>> uploadToBox(String userId, HashMap<String, Object> sessionAttributes,
			String siteId, String boxFolderId, String migrationId,
			MigrationRepository repository, BoxAuthUserRepository uRepository) throws InterruptedException {
		// the HashMap object to be returned
		HashMap<String, String> rvMap = new HashMap<String, String>();
		rvMap.put("userId", userId);
		rvMap.put("siteId", siteId);
		rvMap.put("migrationId", migrationId);

		StringBuffer boxMigrationStatus = new StringBuffer();
		List<MigrationFileItem> itemMigrationStatus = new ArrayList<MigrationFileItem>();
		
		// the HashMap object holds itemized status information
		HashMap<String, Object> statusMap = new HashMap<String, Object>();
		statusMap.put(Utils.MIGRATION_STATUS, boxMigrationStatus.toString());
		statusMap.put(Utils.MIGRATION_DATA, itemMigrationStatus);
		
		// update the status and end_time of migration record
		setMigrationEndTimeAndStatus(migrationId, repository, new JSONObject(statusMap));

		rvMap.put(Utils.MIGRATION_STATUS, Utils.STATUS_SUCCESS);
		return new AsyncResult<HashMap<String, String>>(rvMap);
	}
	
	/**
	 * iterating though content json and upload folders and files to Box
	 */
	public List<MigrationFileItem> boxUploadSiteContent(
			String migration_id, HttpContext httpContext, String userId, String sessionId,
			String siteResourceJson, String boxFolderId) {

		List<MigrationFileItem> rv = new ArrayList<MigrationFileItem>();

		// site root folder
		String rootFolderPath = null;

		JSONObject obj = new JSONObject(siteResourceJson);

		JSONArray array = obj
				.getJSONArray(CONTENT_JSON_ATTR_CONTENT_COLLECTION);

		// start a stack object, with element of site folder ids
		// the top of the stack is the current container folder
		// since the CTools site content json feed is depth-first search,
		// we can use the stack to store the current folder id,
		// and do pop() when moving to a different folder
		java.util.Stack<String> containerStack = new java.util.Stack<String>();
		// this is the parallel stack which stored the Box folder of those
		// container collections
		java.util.Stack<String> boxFolderIdStack = new java.util.Stack<String>();

		for (int i = 0; i < array.length(); i++) {
			// error flag
			boolean error_flag = false;

			// status for each item
			StringBuffer itemStatus = new StringBuffer();

			JSONObject contentItem = array.getJSONObject(i);

			String type = Utils.getJSONString(contentItem,
					CONTENT_JSON_ATTR_TYPE);
			String title = Utils.getJSONString(contentItem,
					CONTENT_JSON_ATTR_TITLE);
			String description = Utils.getJSONString(contentItem,
					CONTENT_JSON_ATTR_DESCRIPTION);
			// metadata
			String author = Utils.getJSONString(contentItem,
					CONTENT_JSON_ATTR_AUTHOR);
			String copyrightAlert = Utils.getJSONString(contentItem,
					CONTENT_JSON_ATTR_COPYRIGHT_ALERT);

			// get only the url after "/access/content" string
			String contentAccessUrl = contentItem
					.getString(CONTENT_JSON_ATTR_URL);
			String contentUrl = getContentUrl(contentAccessUrl);
			if (contentUrl == null) {
				itemStatus.append("Content url " + contentUrl
						+ " does not contain " + CTOOLS_ACCESS_STRING + " nor "
						+ CTOOLS_CITATION_ACCESS_STRING);

				// document the error and break
				MigrationFileItem item = new MigrationFileItem(contentUrl,
						title, itemStatus.toString());
				rv.add(item);
				break;
			}

			// modify the contentAccessUrl if needed for copyright alert setting
			// always export the resource content regardless of the copyright
			// settings
			contentAccessUrl = Utils.getCopyrightAcceptUrl(copyrightAlert,
					contentAccessUrl);

			// get container string from content url
			String container = getContainerStringFromContentUrl(contentUrl);

			// come checkpoints before migration
			itemStatus = preMigrationChecks(itemStatus, contentUrl, container,
					title);

			log.info("type=" + type + " contentUrl=" + contentUrl + " error="
					+ itemStatus.toString());

			if (itemStatus.length() == 0) {
				// now alerts, do Box uploads next
				if (rootFolderPath == null
						&& Utils.COLLECTION_TYPE.equals(type)) {
					// root folder
					rootFolderPath = contentUrl;

					// insert into stack
					containerStack.push(contentUrl);
					boxFolderIdStack.push(boxFolderId);
				} else {
					// value not null only for Web Link Item
					String webLinkUrl = Utils.getJSONString(contentItem,
							CONTENT_JSON_ATTR_WEB_LINK_URL);
					try {
						// do uploads
						HashMap<String, Object> rvValues = processAddBoxFolders(
								migration_id,
								userId, type, rootFolderPath, contentUrl,
								containerStack, boxFolderIdStack, title,
								container, boxFolderId, itemStatus,
								description, contentItem, httpContext,
								webLinkUrl, contentAccessUrl, author,
								copyrightAlert, sessionId);
						itemStatus = (StringBuffer) rvValues.get("itemStatus");
						containerStack = (java.util.Stack<String>) rvValues
								.get("containerStack");
						boxFolderIdStack = (java.util.Stack<String>) rvValues
								.get("boxFolderIdStack");
						log.debug("containerStack length="
								+ containerStack.size());
						log.debug("boxFolderStack length="
								+ boxFolderIdStack.size());
					} catch (BoxAPIException e) {
						log.error(this + " boxUploadSiteContent "
								+ e.getResponse());
						JSONObject eJSON = new JSONObject(e.getResponse());
						String errorMessage = eJSON.has("context_info") ? eJSON
								.getString("context_info") : "";
						itemStatus
								.append("Box upload process was stopped due to the following error. Please rename the folder/resource item and migrate site again: \""
										+ errorMessage + "\"");
						// the status of file upload to Box
						MigrationFileItem item = new MigrationFileItem(
								contentUrl, title, itemStatus.toString());
						rv.add(item);

						// catch the BoxAPIException e
						// and halt the whole upload process
						break;
					}
				}

			}

			// exclude the root folder level in the status report
			if (i == 0)
				continue;
			
			// the status of file upload to Box
			MigrationFileItem item = new MigrationFileItem(contentUrl, title,
					itemStatus.toString());
			rv.add(item);
		} // for

		return rv;
	}
	

	/**
	 * get substring of contentAccessUrl
	 * 
	 * @param itemStatus
	 * @param contentAccessUrl
	 * @return
	 */
	private String getContentUrl(String contentAccessUrl) {
		String contentUrl = URLDecoder.decode(contentAccessUrl);
		if (contentUrl.contains(CTOOLS_ACCESS_STRING)) {
			// non-citation resource
			contentUrl = contentUrl.substring(contentUrl
					.indexOf(CTOOLS_ACCESS_STRING)
					+ CTOOLS_ACCESS_STRING.length());
		} else if (contentUrl.contains(CTOOLS_CITATION_ACCESS_STRING)) {
			// citation resource
			contentUrl = contentUrl.substring(contentUrl
					.indexOf(CTOOLS_CITATION_ACCESS_STRING)
					+ CTOOLS_CITATION_ACCESS_STRING.length());
		} else {
			// log error
			contentUrl = null;
		}
		return contentUrl;
	}

	/**
	 * perform couple of checks before migration starts
	 * 
	 * @param itemStatus
	 * @param contentUrl
	 * @param container
	 * @param title
	 * @param copyrightAlert
	 */
	private StringBuffer preMigrationChecks(StringBuffer itemStatus,
			String contentUrl, String container, String title) {
		if (contentUrl == null || contentUrl.length() == 0) {
			// log error if the content url is missing
			String urlError = "No url for content " + title;
			log.error(urlError);
			itemStatus.append(urlError + Utils.LINE_BREAK);
		} else if (container == null || container.length() == 0) {
			// log error if the content url is missing
			String containerError = "No container folder url for content "
					+ title;
			log.error(containerError);
			itemStatus.append(containerError + Utils.LINE_BREAK);
		}
		return itemStatus;
	}

	/**
	 * process content JSON file
	 * @param migrationId
	 * @param userId
	 * @param type
	 * @param rootFolderPath
	 * @param contentUrl
	 * @param containerStack
	 * @param boxFolderIdStack
	 * @param title
	 * @param container
	 * @param boxFolderId
	 * @param api
	 * @param itemStatus
	 * @param description
	 * @param contentItem
	 * @param httpContext
	 * @param webLinkUrl
	 * @param contentAccessUrl
	 * @param author
	 * @param copyrightAlert
	 * @param sessionId
	 * @return
	 * @throws BoxAPIException
	 */
	private HashMap<String, Object> processAddBoxFolders(String migrationId, String userId,
			String type, String rootFolderPath, String contentUrl,
			java.util.Stack<String> containerStack,
			java.util.Stack<String> boxFolderIdStack, String title,
			String container, String boxFolderId,
			StringBuffer itemStatus, String description,
			JSONObject contentItem, HttpContext httpContext, String webLinkUrl,
			String contentAccessUrl, String author, String copyrightAlert,
			String sessionId) throws BoxAPIException {

		if (Utils.COLLECTION_TYPE.equals(type)) {
			// folders

			log.info("Begin to create folder " + title);

			// pop the stack till the container equals to stack top
			while (!containerStack.empty()
					&& !container.equals(containerStack.peek())) {
				// sync pops
				containerStack.pop();
				boxFolderIdStack.pop();
			}
			
			BoxAPIConnection api = BoxUtils.getBoxAPIConnection(userId, uRepository);
			if (api == null) {
				// exit if no Box API connection could be made
				// returning all changed variables
				HashMap<String, Object> rv = new HashMap<String, Object>();
				rv.put("itemStatus", "Cannot create Box folder for folder " + title);
				rv.put("containerStack", containerStack);
				rv.put("boxFolderIdStack", boxFolderIdStack);
				return rv;
			}

			// create box folder
			BoxFolder parentFolder = new BoxFolder(api, boxFolderIdStack.peek());
			String sanitizedTitle = Utils.sanitizeName(type, title);
			try {
				BoxFolder.Info childFolderInfo = parentFolder
						.createFolder(sanitizedTitle);
				itemStatus.append("folder " + sanitizedTitle + " created.");

				// push the current folder id into the stack
				containerStack.push(contentUrl);
				boxFolderIdStack.push(childFolderInfo.getID());
				log.debug("top of stack folder id = " + containerStack.peek()
						+ " " + " container folder id=" + container);

				// get the BoxFolder object, get BoxFolder.Info object,
				// set description, and commit change
				BoxFolder childFolder = childFolderInfo.getResource();
				childFolderInfo.setDescription(description);
				childFolder.updateInfo(childFolderInfo);

			} catch (BoxAPIException e) {
				if (e.getResponseCode() == org.apache.http.HttpStatus.SC_CONFLICT) {
					// 409 means name conflict - item already existed
					itemStatus.append("There is already a folder with name "
							+ title + "- folder was not created in Box");

					String exisingFolderId = getExistingBoxFolderIdFromBoxException(
							e, sanitizedTitle);
					if (exisingFolderId != null) {
						// push the current folder id into the stack
						containerStack.push(contentUrl);
						boxFolderIdStack.push(exisingFolderId);
						log.error("top of stack folder id = "
								+ containerStack.peek() + " "
								+ " container folder id=" + container);
					} else {
						log.error("Cannot find conflicting Box folder id for folder name "
								+ sanitizedTitle);
					}
				} else {
					// log the exception message
					log.error(e.getResponse() + " for " + title);

					// and throws the exception,
					// so that the parent function can catch it and stop the
					// whole upload process
					throw e;
				}
			}
		} else {
			// files
			long size = Utils.getJSONLong(contentItem, CONTENT_JSON_ATTR_SIZE);

			// check whether the file size exceeds Box's limit
			if (size >= MAX_CONTENT_SIZE_FOR_BOX) {
				// stop upload this file
				itemStatus.append(title + " is of size " + size
						+ ", too big to be uploaded to Box" + Utils.LINE_BREAK);
			} else {
				// Call the uploadFile method to upload file to Box.
				//
				log.debug("file stack peek= " + containerStack.peek() + " "
						+ " container=" + container);

				while (!containerStack.empty()
						&& !container.equals(containerStack.peek())) {
					// sync pops
					containerStack.pop();
					boxFolderIdStack.pop();
				}

				if (boxFolderIdStack.empty()) {
					String parentError = "Cannot find parent folder for file "
							+ contentUrl;
					log.error(parentError);
				} else {
					// insert records into database
					// ready for multi-thread processing 
					log.info(" time to insert file record folder id=" + boxFolderIdStack.peek() );
					MigrationBoxFile mFile = new MigrationBoxFile(migrationId, userId, boxFolderIdStack.peek(),
							type, title, webLinkUrl,
							contentAccessUrl, description, author,
							copyrightAlert, size, null,
							null, null);
					fRepository.save(mFile);
				}
			}
		}

		// returning all changed variables
		HashMap<String, Object> rv = new HashMap<String, Object>();
		rv.put("itemStatus", itemStatus);
		rv.put("containerStack", containerStack);
		rv.put("boxFolderIdStack", boxFolderIdStack);
		return rv;
	}

	/**
	 * upload file to Box
	 * @param id
	 * @param userId
	 * @param type
	 * @param boxFolderId
	 * @param fileName
	 * @param webLinkUrl
	 * @param fileAccessUrl
	 * @param fileDescription
	 * @param fileAuthor
	 * @param fileCopyrightAlert
	 * @param fileSize
	 * @param sectionId
	 * @return
	 */
	@Async
	protected Future<String> uploadBoxFile(String id, String userId, String type, String boxFolderId, String fileName,
			String webLinkUrl, String fileAccessUrl, String fileDescription,
			String fileAuthor, String fileCopyrightAlert, 
			final long fileSize, HttpContext httpContext) {
		
		BoxAPIConnection api = BoxUtils.getBoxAPIConnection(userId, uRepository);
		
		// status string
		StringBuffer status = new StringBuffer();

		log.info("begin to upload file " + fileName + " to box folder "
				+ boxFolderId);

		log.info("*** " + fileAccessUrl);

		// record zip status
		StringBuffer zipFileStatus = new StringBuffer();
		// create httpclient
		HttpClient httpClient = HttpClientBuilder.create().build();
		InputStream content = null;
		
		try {
			// get file content from /access url
			HttpGet getRequest = new HttpGet(fileAccessUrl);
			getRequest.setHeader("Content-Type",
					"application/x-www-form-urlencoded");
			HttpResponse r = httpClient.execute(getRequest, httpContext);
			
			content = r.getEntity().getContent();
			
			if (Utils.isOfURLMIMEType(type)) {
				try {
					// special handling of Web Links resources
					content = new ByteArrayInputStream(Utils.getWebLinkContent(
							fileName, webLinkUrl).getBytes());
				} catch (java.net.MalformedURLException e) {
					// return status with error message
					status.append("Link "
							+ fileName
							+ " could not be migrated. Please change the link name to be the complete URL and migrate the site again.");
					return new AsyncResult<String>(status.toString());
				}
			}
		} catch (java.io.IOException e) {
			log.info(this + " uploadFile: cannot get web link contenet "
					+ e.getMessage());
		}

		// update file name
		fileName = Utils.modifyFileNameOnType(type, fileName);

		// exit if content stream is null
		if (content == null)
			return null;
		
		BufferedInputStream bContent = null;
		try {

			bContent = new BufferedInputStream(content);
			BoxFolder folder = new BoxFolder(api, boxFolderId);
			log.info("upload file size " + fileSize + " to folder " + folder.getID());
			
			BoxFile.Info newFileInfo = folder.uploadFile(bContent,
					Utils.sanitizeName(type, fileName),
					fileSize, new ProgressListener() {
						public void onProgressChanged(long numBytes,
								long totalBytes) {
							log.info(numBytes + " out of total bytes "
									+ totalBytes);
						}
					});
			
			// get the BoxFile object, get BoxFile.Info object, set description,
			// and commit change
			BoxFile newFile = newFileInfo.getResource();
			newFileInfo.setDescription(fileDescription);
			newFile.updateInfo(newFileInfo);

			// assign meta data
			Metadata metaData = new Metadata();
			metaData.add("/copyrightAlert",
					fileCopyrightAlert == null ? "false" : "true");
			metaData.add("/author", fileAuthor);
			newFile.createMetadata(metaData);

			log.info("upload success for file " + fileName);
		} catch (BoxAPIException e) {
			if (e.getResponseCode() == org.apache.http.HttpStatus.SC_CONFLICT) {
				// 409 means name conflict - item already existed
				String conflictString = "There is already a file with name "
						+ fileName + " - file was not added to Box";
				log.info(conflictString);
				status.append(conflictString + Utils.LINE_BREAK);
			}
			log.error(this + "uploadFile fileName=" + fileName
					+ e.getResponse());
		} catch (IllegalArgumentException iException) {
			String ilExceptionString = "problem creating BufferedInputStream for file "
					+ fileName
					+ " with content and length "
					+ fileSize
					+ iException;
			log.warn(ilExceptionString);
			status.append(ilExceptionString + Utils.LINE_BREAK);
		} catch (Exception e) {
			String ilExceptionString = "problem creating BufferedInputStream for file "
					+ fileName + " with content and length " + fileSize + e;
			log.warn(ilExceptionString);
			status.append(ilExceptionString + Utils.LINE_BREAK);
		} finally {
			if (bContent != null) {
				try {
					bContent.close(); // The BufferedInputStream needs to be
										// closed
				} catch (IOException ioException) {
					String ioExceptionString = "problem closing FileChannel for file "
							+ fileName + " " + ioException;
					log.error(ioExceptionString);
					status.append(ioExceptionString + Utils.LINE_BREAK);
				}
			}
		}
		if (content != null) {
			try {
				content.close(); // The input stream needs to be closed
			} catch (IOException ioException) {
				String ioExceptionString = "zipFiles: problem closing Inputstream content for file "
						+ fileName + " " + ioException;
				log.error(ioExceptionString);
				status.append(ioExceptionString + Utils.LINE_BREAK);
			}
		}

		// box upload success
		if (status.length() == 0) {
			status.append("Box upload successful for file " + fileName + ".");
		}
		
		// update the status and end time for file item
		fRepository.setMigrationBoxFileEndTime(id, new java.sql.Timestamp(System.currentTimeMillis()));
		fRepository.setMigrationBoxFileStatus(id, status.toString());
		
		return new AsyncResult<String>(status.toString());
	}

	/**
	 * Based on the JSON returned inside BoxAPIException object, find out the id
	 * of conflicting box folder
	 * 
	 * @return id
	 */
	private String getExistingBoxFolderIdFromBoxException(BoxAPIException e,
			String folderTitle) {
		String existingFolderId = null;
		// here is the example JSON returned
		// {
		// "type":"error",
		// "status":409,
		// "code":"item_name_in_use",
		// "context_info":{
		// "conflicts":[
		// {
		// "type":"folder",
		// "id":"5443268429",
		// "sequence_id":"0",
		// "etag":"0",
		// "name":"folder1"
		// }
		// ]
		// },
		// "help_url":"http:\/\/developers.box.com\/docs\/#errors",
		// "message":"Item with the same name already exists",
		// "request_id":"153175908556537d483098d"
		// }
		if (e.getResponse() == null)
			return null;
		JSONObject boxException = new JSONObject(e.getResponse());
		if (boxException == null)
			return null;
		JSONObject context_info = boxException.getJSONObject("context_info");
		if (context_info == null)
			return null;
		JSONArray conflicts = context_info.getJSONArray("conflicts");
		if (conflicts == null)
			return null;

		for (int index = 0; index < conflicts.length(); index++) {
			JSONObject conflict = conflicts.getJSONObject(index);
			String conflictType = conflict.getString("type");
			if (conflictType != null && conflictType.equals("folder")) {
				String folderId = conflict.getString("id");
				String folderName = conflict.getString("name");
				if (folderName != null && folderName.equals(folderTitle)) {
					// found the existing folder id, break
					existingFolderId = folderId;
					break;
				}
			}
		}
		return existingFolderId;
	}
	
	
	/**************** MailArchive content ***********/
	/**
	 * Download MailArchive resource in zip file
	 * @param env
	 * @param request
	 * @param response
	 * @param userId
	 * @param sessionAttributes
	 * @param site_id
	 * @param migrationId
	 * @param repository
	 */
	public void downloadMailArchiveZipFile(Environment env, HttpServletRequest request,
			HttpServletResponse response, String userId,
			HashMap<String, Object> sessionAttributes, String site_id,
			String migrationId, MigrationRepository repository) {
		// hold download status

		JSONObject downloadStatus = new JSONObject();

		// login to CTools and get sessionId
		if (sessionAttributes.containsKey(Utils.SESSION_ID)) {
			String sessionId = (String) sessionAttributes.get(Utils.SESSION_ID);
			HttpContext httpContext = (HttpContext) sessionAttributes
					.get("httpContext");
			try {
				//
				// Sends the response back to the user / browser. The
				// content for zip file type is "application/zip". We
				// also set the content disposition as attachment for
				// the browser to show a dialog that will let user
				// choose what action will he do to the sent content.
				//
				response.setContentType(Utils.MIME_TYPE_ZIP);
				String zipFileName = site_id + "_mailarchive.zip";
				response.setHeader("Content-Disposition",
						"attachment;filename=\"" + zipFileName + "\"");
	
				ZipOutputStream out = new ZipOutputStream(
						response.getOutputStream());
				// set compression level to high
				out.setLevel(9);
	
				log.info("begin: start downloading mail archive zip file for site " + site_id);
	
				downloadStatus = getMailArchiveZipContent(env, site_id, downloadStatus,
						sessionId, httpContext, out);
				
				out.flush();
				out.close();
				log.info("Finished mail archive zip download for site " + site_id);
				

			} catch (RestClientException e) {
				String errorMessage = Utils.STATUS_FAILURE + " Cannot find site by siteId: " + site_id
						+ " " + e.getMessage();
				Response.status(Response.Status.NOT_FOUND).entity(errorMessage)
						.type(MediaType.TEXT_PLAIN).build();
				log.error(errorMessage);
				downloadStatus.put("status", errorMessage);
			}
			catch (IOException e) {
				String errorMessage = Utils.STATUS_FAILURE + " Problem getting mail archive zip file for "
						+ site_id + " " + e.getMessage();
				Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorMessage).type(MediaType.TEXT_PLAIN)
						.build();
				log.error(errorMessage);
				downloadStatus.put("status", errorMessage);

			} catch (Exception e) {
				String errorMessage = Utils.STATUS_FAILURE + " Migration status for " + site_id + " "
						+ e.getClass().getName();
				Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorMessage).type(MediaType.TEXT_PLAIN)
						.build();
				log.error("downloadMailArchiveZipFile ", e);
				downloadStatus.put("status", errorMessage);
			}
		} else {
			String userError = "Cannot become user " + userId;
			log.error(userError);
			downloadStatus.put("status", userError);
		}

		// update the status and end_time of migration record
		setMigrationEndTimeAndStatus(migrationId, repository, downloadStatus);

		return;

	}

	/**
	 * get MailArchive message content into ZipOutputStream
	 * @param env
	 * @param site_id
	 * @param downloadStatus
	 * @param sessionId
	 * @param httpContext
	 * @param out
	 * @return
	 * @throws IOException
	 */
	private JSONObject getMailArchiveZipContent(Environment env, String site_id,
			JSONObject downloadStatus, String sessionId,
			HttpContext httpContext, ZipOutputStream out) throws IOException {

		JSONArray messagesStatus = new JSONArray();
		
		// get all mail channels inside the site
		RestTemplate restTemplate = new RestTemplate();
		String requestUrl = env.getProperty(Utils.ENV_PROPERTY_CTOOLS_SERVER_URL)
				+ "direct/mailarchive/siteChannels/" + site_id + ".json?_sessionId="
				+ sessionId;
		JSONObject channelsJSON = null;
		channelsJSON = new JSONObject(restTemplate.getForObject(requestUrl,
				String.class));
		
		if (!channelsJSON.has(Utils.JSON_ATTR_MAILARCHIVE_COLLECTION))
		{
			return downloadStatus;
		}
		
		JSONArray channels = channelsJSON.getJSONArray(Utils.JSON_ATTR_MAILARCHIVE_COLLECTION);
		
		boolean folderForChannels = false;
		if (channels.length() > 1)
		{
			// if the site have more than one MailArchive channel
			// create zip folder for each channel
			folderForChannels = true;
		}
		for (int iChannel = 0; iChannel < channels.length(); iChannel++) {
			JSONObject channel = channels.getJSONObject(iChannel);
			String channelId = channel.getString("data");
			channelId = channelId.substring(("/mailarchive/channel/" + site_id + "/").length());
			String channelName = channel.getString("displayTitle");
			
			if (folderForChannels)
			{
				ZipEntry folderEntry = new ZipEntry(channelName + "/");
				try {
					out.putNextEntry(folderEntry);
				} catch (IOException e) {
					String ioError = "downloadMailArchiveZipFile: problem adding zip folder for MailArchive channel "
							+ channelName + " " + e;
					log.error(ioError);
				}
			}
			
			// get all email messages in the channel
			requestUrl = env.getProperty(Utils.ENV_PROPERTY_CTOOLS_SERVER_URL)
					+ "direct/mailarchive/channelMessages/" + site_id + "/" + channelId + ".json?_sessionId="
					+ sessionId;
			JSONObject messagesJSON = new JSONObject(restTemplate.getForObject(requestUrl,
					String.class));
			JSONArray messages = messagesJSON.getJSONArray(Utils.JSON_ATTR_MAILARCHIVE_COLLECTION);
			for (int iMessage = 0; iMessage < messages.length(); iMessage++) {
				JSONObject message = messages.getJSONObject(iMessage);
				
				// create file for each message
				String messageFolderName = getMailArchiveMessageFolderName(message, channelName, folderForChannels);
						
				JSONObject messageStatus = new JSONObject();
				messageStatus.put(Utils.JSON_ATTR_MAIL_MESSAGE, messageFolderName);
				
				// 1. write the message file
				messageStatus = handleMailArchiveMessage(out, message, messageFolderName,
						messageStatus);
				
				// 2. get attachments, if any
				messageStatus = handleMailArchiveMessageAttachments(
						sessionId, httpContext, out, message, messageFolderName, messageStatus);
				
				messagesStatus.put(messageStatus);
			}
		}
		
		downloadStatus.put(Utils.MIGRATION_DATA, messagesStatus);
		downloadStatus.put(Utils.MIGRATION_STATUS, messagesStatus.toString().contains(Utils.STATUS_FAILURE)? Utils.STATUS_FAILURE: Utils.STATUS_SUCCESS);

		return downloadStatus;
	}

	/**
	 * output MailArchive message content
	 * @param out
	 * @param message
	 * @param messageFolderName
	 * @param messageStatus
	 * @return
	 * @throws IOException
	 */
	private JSONObject handleMailArchiveMessage(ZipOutputStream out,
			JSONObject message, String messageFolderName,
			JSONObject messageStatus) throws IOException {
		try {
			// get the html file content first
			String messageContent = message.has(Utils.JSON_ATTR_MAIL_BODY)?message.getString(Utils.JSON_ATTR_MAIL_BODY):"";

			ZipEntry fileEntry = new ZipEntry(messageFolderName + Utils.MAIL_MESSAGE_FILE_NAME);
			out.putNextEntry(fileEntry);
			
			// output message header info
			JSONArray headers = message.getJSONArray(Utils.JSON_ATTR_MAIL_HEADERS);
			for (int iHeader = 0; iHeader<headers.length(); iHeader++)
			{
				String header = headers.getString(iHeader) + "\r\n";
				out.write(header.getBytes());
			}
			out.write("\r\n".getBytes());
			// output message body
			out.write(messageContent.getBytes());
			

			messageStatus.put(Utils.JSON_ATTR_MAIL_MESSAGE_STATUS, Utils.STATUS_SUCCESS);
		} catch (java.net.MalformedURLException e) {
			// return status with error message
			messageStatus.put(Utils.JSON_ATTR_MAIL_MESSAGE_STATUS, Utils.STATUS_FAILURE + " problem getting message content" + e.getMessage());
		} catch (IOException e) {
			// return status with error message
			messageStatus.put(Utils.JSON_ATTR_MAIL_MESSAGE_STATUS, Utils.STATUS_FAILURE + " problem getting message content" + e.getMessage());
		}
		return messageStatus;
	}

	/**
	 * put mail message attachments into zip
	 * @param sessionId
	 * @param httpContext
	 * @param out
	 * @param message
	 * @param messageFolderName
	 * @return
	 */
	private JSONObject handleMailArchiveMessageAttachments(String sessionId,
			HttpContext httpContext, ZipOutputStream out, JSONObject message,
			String messageFolderName, JSONObject messageStatus) {
		JSONArray attachmentsStatus = new JSONArray();
		JSONArray attachments = message.getJSONArray(Utils.JSON_ATTR_MAIL_ATTACHMENTS);
		for (int iAttachment = 0; iAttachment < attachments.length(); iAttachment++) {
			// get each attachment
			JSONObject attachment = attachments.getJSONObject(iAttachment);
			String type = attachment.has(Utils.JSON_ATTR_MAIL_TYPE)?attachment.getString(Utils.JSON_ATTR_MAIL_TYPE):"";
			String name = attachment.has(Utils.JSON_ATTR_MAIL_NAME)?attachment.getString(Utils.JSON_ATTR_MAIL_NAME):"";
			String url = attachment.has(Utils.JSON_ATTR_MAIL_URL)?attachment.getString(Utils.JSON_ATTR_MAIL_URL):"";
			// Call the zipFiles method for creating a zip stream.
			String fileStatus = zipFiles(type, httpContext,
					messageFolderName + name, name, "", url,
					sessionId, out, new HashMap<String, String>());
			JSONObject attachmentStatus = new JSONObject();
			attachmentStatus.put(Utils.JSON_ATTR_MAIL_NAME, name);
			attachmentStatus.put(Utils.MIGRATION_STATUS, fileStatus);
			
			// add the attachment status to the list
			attachmentsStatus.put(attachmentStatus);
		}
		
		// update message status
		messageStatus.put(Utils.JSON_ATTR_MAIL_ATTACHMENTS, attachmentsStatus);
		
		return messageStatus;
	}
	
	/**
	 * construct the zip folder name for a MailArchive message
	 * @param message
	 * @param channelName
	 * @param folderForChannels
	 * @return
	 */
	private String getMailArchiveMessageFolderName(JSONObject message, String channelName, boolean folderForChannels)
	{
		// get message information from header
		JSONArray headers = message.getJSONArray(Utils.JSON_ATTR_MAIL_HEADERS);
		String subject = getHeaderAttribute(headers, Utils.JSON_ATTR_MAIL_SUBJECT);
		String sender = getHeaderAttribute(headers, Utils.JSON_ATTR_MAIL_FROM);
		String date = getHeaderAttribute(headers, Utils.JSON_ATTR_MAIL_DATE);
		
		// create file for each message
		String messageFolderName = "";
		if (folderForChannels)
		{
			messageFolderName = channelName + Utils.PATH_SEPARATOR;
		}
		messageFolderName = Utils.sanitizeName(Utils.COLLECTION_TYPE, messageFolderName + date + " " + sender + " " + subject) + "/";
		
		return messageFolderName;
	}
	
	/**
	 * get mail message info from header
	 * @param headers
	 * @param attribute
	 * @return
	 */
	private String getHeaderAttribute(JSONArray headers, String attribute)
	{
		String rv = "";
		for (int iHeader = 0; iHeader < headers.length(); iHeader++) {
			String header = headers.getString(iHeader);
			if (header.startsWith(attribute))
			{
				rv = header.substring(attribute.length());
				break;
			}
		}
		
		return rv;
	}
	
	public HashMap<String, String> processAddEmailMessages(
			HttpServletRequest request, HttpServletResponse response,
			String target, String remoteUser, HashMap<String, String> rv,
			String googleGroupId, String siteId, String toolId,
			HashMap<String, Object> saveMigration) {
		if (!saveMigration.containsKey("migration")) {
			// no new Migration record created
			rv.put("errorMessage", "Cannot create migration records for user "
					+ remoteUser + " and site=" + siteId);
			return rv;
		} 

		Migration migration = (Migration) saveMigration.get("migration");
		String migrationId = migration.getMigration_id();
		
		// get session
		HashMap<String, Object> sessionAttributes = Utils
				.login_becomeuser(env, request, remoteUser);
		if (sessionAttributes == null || !sessionAttributes.containsKey(Utils.SESSION_ID)) {
			rv.put("errorMessage", "Cannot create become user "
					+ remoteUser + " and site=" + siteId);
			return rv;
		}
		
		String sessionId = (String) sessionAttributes.get(Utils.SESSION_ID);

		// get all mail channels inside the site
		RestTemplate restTemplate = new RestTemplate();
		String requestUrl = env.getProperty(Utils.ENV_PROPERTY_CTOOLS_SERVER_URL)
				+ "direct/mailarchive/siteChannels/" + siteId + ".json?_sessionId="
				+ sessionId;
		JSONObject channelsJSON = null;
		channelsJSON = new JSONObject(restTemplate.getForObject(requestUrl,
				String.class));
		
		if (!channelsJSON.has(Utils.JSON_ATTR_MAILARCHIVE_COLLECTION))
		{
			rv.put("errorMessage", "Cannot get mail archive information for site=" + siteId);
			return rv;
		}
		
		JSONArray channels = channelsJSON.getJSONArray(Utils.JSON_ATTR_MAILARCHIVE_COLLECTION);
		for (int iChannel = 0; iChannel < channels.length(); iChannel++) {
			JSONObject channel = channels.getJSONObject(iChannel);
			String channelId = channel.getString("data");
			channelId = channelId.substring(("/mailarchive/channel/" + siteId + "/").length());
			String channelName = channel.getString("displayTitle");
			
			// get all email messages in the channel
			requestUrl = env.getProperty(Utils.ENV_PROPERTY_CTOOLS_SERVER_URL)
					+ "direct/mailarchive/channelMessages/" + siteId + "/" + channelId + ".json?_sessionId="
					+ sessionId;
			JSONObject messagesJSON = new JSONObject(restTemplate.getForObject(requestUrl,
					String.class));
			JSONArray messages = messagesJSON.getJSONArray(Utils.JSON_ATTR_MAILARCHIVE_COLLECTION);
			for (int iMessage = 0; iMessage < messages.length(); iMessage++) {
				JSONObject message = messages.getJSONObject(iMessage);
				String messageId = message.getString("id");
				// construct the MigrationEmailMessage object
				MigrationEmailMessage mMessage = new MigrationEmailMessage(
						messageId, migrationId, 
						remoteUser, googleGroupId,
						message.toString(), null,
						null, null);
				try
				{
					mRepository.save(mMessage);
				}
				catch (Exception e)
				{
					log.error("Problem saving the MigrationEmailMessage " + messageId + " with GoogleGroupId " + googleGroupId + " into database ");
				}
					
				
			}
		}
		return rv;
	}
	
	/**
	 * TODO
	 * call GG microservice to upload message content
	 * @param googleGroup
	 * @param rcf822Email
	 * @return
	 */
	private String addEmailToGoogleGroup(String googleGroup, String rcf822Email)
	{
		return "success";
	}
	
	/**
	 * TODO
	 * call GG microservice to get Group Groups settings for given site id
	 * @param siteId
	 * @param sessionId
	 * @return
	 */
	public JSONObject createGoogleGroupForSite(String siteId, String sessionId)
	{
		JSONObject emailSettings = new JSONObject();
		emailSettings.put("id", "some_id");
		emailSettings.put("name", "some_name");
		
		return emailSettings;
	}
	
	/**
	 * TODO
	 * get CTools site members into Google Group membership
	 * @param siteId
	 * @param sessionId
	 * @return
	 */
	public String updateGoogleGroupMembershipFromSite(String siteId, String sessionId)
	{
		return "";
	}
	
	/**
	 * migrate email content to Group Group using microservice
	 * @param message
	 * @return
	 */
	@Async
	protected Future<String> uploadMessageToGoogleGroup(MigrationEmailMessage message) {
		
		// status string
		String status = "";
		
		String googleGroupId = message.getGoogle_group_id();

		String messageId = message.getMessage_id();
		log.info("begin to upload message " + messageId  + " to Google Group id = " + googleGroupId);
		
		// TODO need to call email RFC822 formatter once it is ready
		String emailContent = message.getJson();
		status = addEmailToGoogleGroup(googleGroupId, emailContent);
		
		// update the status and end time for file item
		mRepository.setMigrationMessageEndTime(messageId, new java.sql.Timestamp(System.currentTimeMillis()));
		mRepository.setMigrationMessageStatus(messageId, status);
		
		return new AsyncResult<String>(status);
	}



}