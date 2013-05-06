package jp.redmine.redmineclient.task;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.net.Uri;
import android.net.Uri.Builder;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;
import android.widget.ArrayAdapter;
import jp.redmine.redmineclient.entity.RedmineConnection;
import jp.redmine.redmineclient.parser.BaseParser;
import jp.redmine.redmineclient.url.RemoteUrl;
import jp.redmine.redmineclient.url.RemoteUrl.requests;
import jp.redmine.redmineclient.url.RemoteUrl.versions;

public abstract class SelectDataTask<T> extends AsyncTask<Integer, Integer, T> {
	public final String CHARSET = "UTF-8";
	/**
	 * Notify error request on UI thread
	 * @param statuscode http response code
	 */
	abstract protected void onErrorRequest(int statuscode);
	/**
	 * Notify progress on UI thread
	 * @param max total count of the items
	 * @param proc current count of the items
	 */
	abstract protected void onProgress(int max,int proc);

	/**
	 * Store the last exception (reference by UI thread)
	 */
	private volatile Exception lasterror;

	interface ProgressKind{
		public int progress = 1;
		public int error = 2;
		public int unknown = 3;
	}

	@Override
	protected final void onProgressUpdate(Integer... values) {
		super.onProgressUpdate(values);
		switch(values[0]){
		case ProgressKind.progress:
			onProgress(values[1],values[2]);
			break;
		case ProgressKind.error:
			onErrorRequest(values[1]);
			break;
		case ProgressKind.unknown:
			onError(lasterror);
			break;
		default:
		}
	}
	protected void onError(Exception lasterror){
		Log.e("SelectDataTask", "background", lasterror);
	}

	protected void publishProgress(int max,int proc){
		super.publishProgress(ProgressKind.progress,max,proc);
	}

	protected void publishErrorRequest(int status){
		super.publishProgress(ProgressKind.error,status);
	}
	protected void publishError(Exception e){
		lasterror = e;
		super.publishProgress(ProgressKind.unknown);
	}

	protected void helperAddItems(ArrayAdapter<T> listAdapter,List<T> items){
		if(items == null)
			return;
		listAdapter.notifyDataSetInvalidated();
		for (T i : items){
			listAdapter.add(i);
		}
		listAdapter.notifyDataSetChanged();
	}

	protected void helperSetupParserStream(InputStream stream,BaseParser<?,?> parser) throws XmlPullParserException{
		XmlPullParser xmlPullParser = Xml.newPullParser();
		xmlPullParser.setInput(stream, CHARSET);
		parser.setXml(xmlPullParser);
	}

	private boolean isGZipHttpResponse(HttpResponse response) {
		Header header = response.getEntity().getContentEncoding();
		if (header == null) return false;
		String value = header.getValue();
		return (!TextUtils.isEmpty(value) && value.contains("gzip"));
	}
	private boolean isDeflateHttpResponse(HttpResponse response) {
		Header header = response.getEntity().getContentEncoding();
		if (header == null) return false;
		String value = header.getValue();
		return (!TextUtils.isEmpty(value) && value.contains("deflate"));
	}
	protected void fetchData(SelectDataTaskConnectionHandler connectionhandler, RedmineConnection connection,RemoteUrl url,SelectDataTaskDataHandler handler){
		url.setupRequest(requests.xml);
		url.setupVersion(versions.v130);
		fetchData(connectionhandler, url.getUrl(connection.getUrl()),handler);
	}


	protected void fetchData(SelectDataTaskConnectionHandler connectionhandler,Builder builder,SelectDataTaskDataHandler handler){
		Uri remoteurl = builder.build();
		DefaultHttpClient client = connectionhandler.getHttpClient();
		boolean isInError = true;
		try {
			HttpGet get = new HttpGet(new URI(remoteurl.toString()));
			connectionhandler.setupOnMessage(get);
			get.setHeader("Accept-Encoding", "gzip, deflate");
			Log.i("requestGet", "Url: " + get.getURI().toASCIIString());
			HttpResponse response = client.execute(get);
			int status = response.getStatusLine().getStatusCode();
			Log.i("requestGet", "Status: " + status);
			Log.i("requestGet", "Protocol: " + response.getProtocolVersion());
			InputStream stream = response.getEntity().getContent();
			if (isGZipHttpResponse(response)) {
				Log.i("requestGet", "Gzip: Enabled");
				stream =  new GZIPInputStream(stream);
			} else if(isDeflateHttpResponse(response)){
				Log.i("requestGet", "Deflate: Enabled");
				stream =  new InflaterInputStream(stream);
			}
			if (HttpStatus.SC_OK == status) {
			    isInError = false;
				handler.onContent(stream);
			} else {
				publishErrorRequest(status);
				BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
			    String str;
			    while((str = reader.readLine()) != null){
			    	Log.d("requestGet", str);
			    }
			}
		} catch (URISyntaxException e) {
			publishErrorRequest(404);
		} catch (ClientProtocolException e) {
			publishError(e);
		} catch (IOException e) {
			publishError(e);
		} catch (XmlPullParserException e) {
			publishError(e);
		} catch (SQLException e) {
			publishError(e);
		}
		if(isInError && "https".equalsIgnoreCase(remoteurl.getScheme()))
			connectionhandler.close();
	}
}
