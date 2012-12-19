/*******************************************************************************
 * Copyright (c) 2012 Jens Kristian Villadsen.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Jens Kristian Villadsen - initial API and implementation
 ******************************************************************************/
package gmusic.api.impl;

import gmusic.api.comm.FormBuilder;
import gmusic.api.comm.HttpUrlConnector;
import gmusic.api.comm.JSON;
import gmusic.api.interfaces.IGoogleHttpClient;
import gmusic.api.interfaces.IGoogleMusicAPI;
import gmusic.api.interfaces.IJsonDeserializer;
import gmusic.api.model.AddPlaylist;
import gmusic.api.model.DeletePlaylist;
import gmusic.api.model.Playlist;
import gmusic.api.model.Playlists;
import gmusic.api.model.QueryResponse;
import gmusic.api.model.Song;
import gmusic.api.model.SongUrl;
import gmusic.model.Tune;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.datatype.Artwork;
import org.jaudiotagger.tag.id3.ID3v24Tag;

import com.google.common.base.Strings;

public class GoogleMusicAPI implements IGoogleMusicAPI
{
	protected final IGoogleHttpClient client;
	protected final IJsonDeserializer deserializer;
	protected final File storageDirectory;

	public GoogleMusicAPI()
	{
		this(new HttpUrlConnector(), new JSON(), new File("."));
	}

	public GoogleMusicAPI(IGoogleHttpClient httpClient, IJsonDeserializer jsonDeserializer, File file)
	{
		client = httpClient;
		deserializer = jsonDeserializer;
		storageDirectory = file;
	}

	@Override
	public final void login(String email, String password) throws IOException, URISyntaxException, InvalidCredentialsException
	{
		Map<String, String> fields = new HashMap<String, String>();
		fields.put("service", "sj");
		fields.put("Email", email);
		fields.put("Passwd", password);

		FormBuilder form = new FormBuilder();
		form.addFields(fields);
		form.close();

		try
		{
			client.dispatchPost(new URI(HTTPS_WWW_GOOGLE_COM_ACCOUNTS_CLIENT_LOGIN), form);
		}
		catch(IllegalStateException ise)
		{
			throw new InvalidCredentialsException(ise, "Provided credentials: '" + email + "' and '" + password + "' where insufficient");
		}
	}

	@Override
	public final Collection<Song> getAllSongs() throws IOException, URISyntaxException
	{
		return getSongs("");
	}

	@Override
	public final AddPlaylist addPlaylist(String playlistName) throws Exception
	{
		Map<String, String> fields = new HashMap<String, String>();
		fields.put("json", "{\"title\":\"" + playlistName + "\"}");

		FormBuilder form = new FormBuilder();
		form.addFields(fields);
		form.close();

		return deserializer.deserialize(client.dispatchPost(new URI(HTTPS_PLAY_GOOGLE_COM_MUSIC_SERVICES_ADDPLAYLIST), form), AddPlaylist.class);
	}

	@Override
	public final Playlists getAllPlaylists() throws IOException, URISyntaxException
	{
		return deserializer.deserialize(getPlaylistAssist("{}"), Playlists.class);
	}

	@Override
	public final Playlist getPlaylist(String plID) throws IOException, URISyntaxException
	{
		return deserializer.deserialize(getPlaylistAssist("{\"id\":\"" + plID + "\"}"), Playlist.class);
	}

	protected final URI getTuneURL(Tune tune) throws URISyntaxException, IOException
	{
		return new URI(deserializer.deserialize(client.dispatchGet(new URI(String.format(HTTPS_PLAY_GOOGLE_COM_MUSIC_PLAY_SONGID, tune.getId()))), SongUrl.class).getUrl());
	}

	@Override
	public URI getSongURL(Song song) throws URISyntaxException, IOException
	{
		return getTuneURL(song);
	}

	@Override
	public final DeletePlaylist deletePlaylist(String id) throws Exception
	{
		Map<String, String> fields = new HashMap<String, String>();
		fields.put("json", "{\"id\":\"" + id + "\"}");

		FormBuilder form = new FormBuilder();
		form.addFields(fields);
		form.close();

		return deserializer.deserialize(client.dispatchPost(new URI(HTTPS_PLAY_GOOGLE_COM_MUSIC_SERVICES_DELETEPLAYLIST), form), DeletePlaylist.class);
	}

	private final String getPlaylistAssist(String jsonString) throws IOException, URISyntaxException
	{
		Map<String, String> fields = new HashMap<String, String>();
		fields.put("json", jsonString);

		FormBuilder builder = new FormBuilder();
		builder.addFields(fields);
		builder.close();

		return client.dispatchPost(new URI(HTTPS_PLAY_GOOGLE_COM_MUSIC_SERVICES_LOADPLAYLIST), builder);
	}

	private final Collection<Song> getSongs(String continuationToken) throws IOException, URISyntaxException
	{
		Collection<Song> chunkedCollection = new ArrayList<Song>();

		Map<String, String> fields = new HashMap<String, String>();
		fields.put("json", "{\"continuationToken\":\"" + continuationToken + "\"}");

		FormBuilder form = new FormBuilder();
		form.addFields(fields);
		form.close();

		Playlist chunk = deserializer.deserialize(client.dispatchPost(new URI(HTTPS_PLAY_GOOGLE_COM_MUSIC_SERVICES_LOADALLTRACKS), form), Playlist.class);
		chunkedCollection.addAll(chunk.getPlaylist());

		if(!Strings.isNullOrEmpty(chunk.getContinuationToken()))
		{
			chunkedCollection.addAll(getSongs(chunk.getContinuationToken()));
		}
		return chunkedCollection;
	}

	@Override
	public Collection<File> downloadSongs(Collection<Song> songs) throws MalformedURLException, IOException, URISyntaxException
	{
		Collection<File> files = new ArrayList<File>();
		for(Song song : songs)
		{
			files.add(downloadSong(song));
		}
		return files;
	}

	@Override
	public File downloadSong(Song song) throws MalformedURLException, IOException, URISyntaxException
	{
		return downloadTune(song);
	}

	@Override
	public QueryResponse search(String query) throws IOException, URISyntaxException
	{
		if(Strings.isNullOrEmpty(query))
		{
			throw new IllegalArgumentException("query is null or empty");
		}

		Map<String, String> fields = new HashMap<String, String>();
		fields.put("json", "{\"q\":\"" + query + "\"}");

		FormBuilder form = new FormBuilder();
		form.addFields(fields);
		form.close();

		String response = client.dispatchPost(new URI(HTTPS_PLAY_GOOGLE_COM_MUSIC_SERVICES_SEARCH), form);

		return deserializer.deserialize(response, QueryResponse.class);
	}

	protected File downloadTune(Song song) throws MalformedURLException, IOException, URISyntaxException
	{
		File file = new File(storageDirectory + System.getProperty("path.separator") + song.getId() + ".mp3");
		if(!file.exists())
		{
			FileUtils.copyURLToFile(getTuneURL(song).toURL(), file);
			populateFileWithTuneTags(file, song);
		}
		return file;
	}

	private void populateFileWithTuneTags(File file, Song song) throws IOException
	{
		try
		{
			AudioFile f = AudioFileIO.read(file);
			Tag tag = f.getTag();
			if(tag == null)
			{
				tag = new ID3v24Tag();
			}
			tag.setField(FieldKey.ALBUM, song.getAlbum());
			tag.setField(FieldKey.ALBUM_ARTIST, song.getAlbumArtist());
			tag.setField(FieldKey.ARTIST, song.getArtist());
			tag.setField(FieldKey.COMPOSER, song.getComposer());
			tag.setField(FieldKey.DISC_NO, String.valueOf(song.getDisc()));
			tag.setField(FieldKey.DISC_TOTAL,
					String.valueOf(song.getTotalDiscs()));
			tag.setField(FieldKey.GENRE, song.getGenre());
			tag.setField(FieldKey.TITLE, song.getTitle());
			tag.setField(FieldKey.TRACK, String.valueOf(song.getTrack()));
			tag.setField(FieldKey.TRACK_TOTAL,
					String.valueOf(song.getTotalTracks()));
			tag.setField(FieldKey.YEAR, String.valueOf(song.getYear()));
			
			if(song.getAlbumArtUrl() != null)
			{
				Artwork artwork = new Artwork();
				File imageFile = new File(storageDirectory + System.getProperty("path.separator") + song.getId() + ".im");
				FileUtils.copyURLToFile(song.getAlbumArtUrlAsURI().toURL(), imageFile);
				artwork.setFromFile(imageFile);
				tag.addField(artwork);				
			}

			f.setTag(tag);
			AudioFileIO.write(f);
		}
		catch(Exception e)
		{
			throw new IOException(e);
		}
	}

	@Override
	public void uploadSong(File song)
	{
		
	}
}
