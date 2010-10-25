package org.helllabs.android.xmp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;


public class PlaylistMenu extends ListActivity {
	static final int SETTINGS_REQUEST = 45;
	static final int PLAYLIST_REQUEST = 46;
	Xmp xmp = new Xmp();
	SharedPreferences prefs;
	String media_path;
	ProgressDialog progressDialog;
	
	
	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		setContentView(R.layout.playlist_menu);
		registerForContextMenu(getListView());
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		xmp.initContext();
		
		ChangeLog changeLog = new ChangeLog(this);
		
		if (!Settings.dataDir.isDirectory()) {
			if (!Settings.dataDir.mkdirs()) {
				Message.fatalError(this, getString(R.string.error_datadir), PlaylistMenu.this);
			} else {
				final String name = getString(R.string.empty_playlist);
				File file = new File(Settings.dataDir, name + ".playlist");
				try {
					file.createNewFile();
					file = new File(Settings.dataDir, name + ".comment");
					file.createNewFile();
					FileUtils.writeToFile(file, getString(R.string.empty_comment));
					updateList();
				} catch (IOException e) {
					Message.error(this, getString(R.string.error_create_playlist));
					return;
				}				
			}
		} else {
			updateList();
		}
		
		changeLog.show();
	}
	
	void updateList() {
		media_path = prefs.getString(Settings.PREF_MEDIA_PATH, Settings.DEFAULT_MEDIA_PATH);
		
		List<PlaylistInfo> list = new ArrayList<PlaylistInfo>();
		
		list.clear();
		list.add(new PlaylistInfo("Module list", "Files in " + media_path));
		
		for (String p : PlaylistUtils.listNoSuffix()) {
			list.add(new PlaylistInfo(p, PlaylistUtils.readComment(this, p)));
		}
		
        final PlaylistInfoAdapter playlist = new PlaylistInfoAdapter(PlaylistMenu.this,
    			R.layout.playlist_item, R.id.plist_info, list);
        
        setListAdapter(playlist);
	}
	
	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		if (position == 0) {
			Intent intent = new Intent(PlaylistMenu.this, ModList.class);
			startActivityForResult(intent, PLAYLIST_REQUEST);
		} else {
			Intent intent = new Intent(PlaylistMenu.this, PlayList.class);
			intent.putExtra("name", PlaylistUtils.listNoSuffix()[position -1]);
			startActivityForResult(intent, PLAYLIST_REQUEST);
		}
	}
	
	
	// Playlist context menu
	
	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
	    AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)menuInfo;
		menu.setHeaderTitle("Playlist options");
		
		if (info.position == 0) {
			menu.add(Menu.NONE, 0, 0, "Change directory");
			menu.add(Menu.NONE, 1, 1, "Files to new playlist");
			int i = 2;
			for (String s : PlaylistUtils.listNoSuffix()) {
				menu.add(Menu.NONE, i, i, "Add to " + s);
				i++;
			}
		} else {
			menu.add(Menu.NONE, 0, 0, "Rename");
			menu.add(Menu.NONE, 1, 1, "Edit comment");
			menu.add(Menu.NONE, 2, 2, "Delete playlist");
		}
	}
	
	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)item.getMenuInfo();
		int index = item.getItemId();
		PlaylistUtils p = new PlaylistUtils();

		if (info.position == 0) {		// First item of list
			switch (index) {
			case 0:						// First item of context menu
				changeDir(this);
				return true;
			case 1:
				p.filesToNewPlaylist(this, new Runnable() {
					public void run() {
						updateList();
					}
				});
				return true;
			default:
				p.filesToPlaylist(this, PlaylistUtils.listNoSuffix()[index - 2]);
				return true;
			}
		} else {
			switch (index) {
			case 0:						// Rename
				renameList(this, info.position -1);
				updateList();
				return true;
			case 1:						// Edit comment
				editComment(this, info.position -1);
				updateList();
				return true;
			case 2:						// Delete
				PlaylistUtils.deleteList(this, info.position - 1);
				updateList();
				return true;
			}			
		}

		return true;
	}
	
	public void renameList(final Context context, int index) {
		final String name = PlaylistUtils.listNoSuffix()[index];
		AlertDialog.Builder alert = new AlertDialog.Builder(context);		  
		alert.setTitle("Rename playlist");  
		alert.setMessage("Enter the new playlist name:");  
		final EditText input = new EditText(context);
		input.setText(name);
		alert.setView(input);  

		alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {  
			public void onClick(DialogInterface dialog, int whichButton) {
				boolean error = false;
				String value = input.getText().toString();
				File old1 = new File(Settings.dataDir, name + ".playlist");
				File old2 = new File(Settings.dataDir, name + ".comment");
				File new1 = new File(Settings.dataDir, value + ".playlist");
				File new2 = new File(Settings.dataDir, value + ".comment");
				
				if (old1.renameTo(new1) == false) { 
					error = true;
				} else if (old2.renameTo(new2) == false) {
					new1.renameTo(old1);
					error = true;
				}
				
				if (error) {
					Message.error(context, getString(R.string.error_rename_playlist));
				}
				
				updateList();
			}  
		});  
			  
		alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {  
			public void onClick(DialogInterface dialog, int whichButton) {  
				// Canceled.  
			}  
		});  

		alert.show(); 

	}
	
	public void changeDir(Context context) {
		AlertDialog.Builder alert = new AlertDialog.Builder(context);		  
		alert.setTitle("Change directory");  
		alert.setMessage("Enter the mod directory:");  
		final EditText input = new EditText(context);
		input.setText(media_path);
		alert.setView(input);  

		alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {  
			public void onClick(DialogInterface dialog, int whichButton) {  
				String value = input.getText().toString();
				if (!value.equals(media_path)) {
					SharedPreferences.Editor editor = prefs.edit();
					editor.putString(Settings.PREF_MEDIA_PATH, value);
					editor.commit();
					updateList();
				}
			}  
		});  
			  
		alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {  
			public void onClick(DialogInterface dialog, int whichButton) {  
				// Canceled.  
			}  
		});  

		alert.show(); 
	}
	
	public void editComment(final Context context, int index) {
		final String name = PlaylistUtils.listNoSuffix()[index];
		AlertDialog.Builder alert = new AlertDialog.Builder(context);		  
		alert.setTitle("Edit comment");  
		alert.setMessage("Enter the new comment for " + name + ":");  
		final EditText input = new EditText(context);
		input.setText(PlaylistUtils.readComment(context, name));
		alert.setView(input);  

		alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {  
			public void onClick(DialogInterface dialog, int whichButton) {  
				String value = input.getText().toString();
				File file = new File(Settings.dataDir, name + ".comment");
				try {
					file.createNewFile();
					FileUtils.writeToFile(file, value);
				} catch (IOException e) {
					Message.error(context, getString(R.string.error_edit_comment));
				}
				
				updateList();
			}  
		});  
			  
		alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {  
			public void onClick(DialogInterface dialog, int whichButton) {
				// Canceled.  
			}  
		});  

		alert.show(); 
	}
	
	@Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    	switch (requestCode) {
    	case SETTINGS_REQUEST:
            if (resultCode == RESULT_OK)
            	updateList();
            break;
    	case PLAYLIST_REQUEST:
    		updateList();
    		break;
        }
    }
	
	
	// Menu
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
	    MenuInflater inflater = getMenuInflater();
	    inflater.inflate(R.menu.options_menu, menu);
	    return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch(item.getItemId()) {
		case R.id.menu_prefs:		
			startActivityForResult(new Intent(this, Settings.class), SETTINGS_REQUEST);
			break;
		case R.id.menu_refresh:
			updateList();
			break;
		}
		return true;
	}

}