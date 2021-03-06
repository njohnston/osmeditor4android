package de.blau.android;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLProtocolException;
import javax.xml.parsers.ParserConfigurationException;

import org.acra.ACRA;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParserException;

import com.drew.lang.annotations.NotNull;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import de.blau.android.contract.Urls;
import de.blau.android.dialogs.AttachedObjectWarning;
import de.blau.android.dialogs.ErrorAlert;
import de.blau.android.dialogs.ForbiddenLogin;
import de.blau.android.dialogs.InvalidLogin;
import de.blau.android.dialogs.Progress;
import de.blau.android.dialogs.ProgressDialog;
import de.blau.android.dialogs.UploadConflict;
import de.blau.android.exception.OsmException;
import de.blau.android.exception.OsmIllegalOperationException;
import de.blau.android.exception.OsmServerException;
import de.blau.android.exception.StorageException;
import de.blau.android.filter.Filter;
import de.blau.android.osm.BoundingBox;
import de.blau.android.osm.Node;
import de.blau.android.osm.OsmElement;
import de.blau.android.osm.OsmParser;
import de.blau.android.osm.PostMergeHandler;
import de.blau.android.osm.Relation;
import de.blau.android.osm.RelationMember;
import de.blau.android.osm.RelationMemberDescription;
import de.blau.android.osm.Server;
import de.blau.android.osm.Server.UserDetails;
import de.blau.android.osm.Server.Visibility;
import de.blau.android.osm.StorageDelegator;
import de.blau.android.osm.Tags;
import de.blau.android.osm.Track;
import de.blau.android.osm.UndoStorage;
import de.blau.android.osm.Way;
import de.blau.android.prefs.Preferences;
import de.blau.android.resources.DataStyle;
import de.blau.android.resources.TileLayerServer;
import de.blau.android.tasks.Note;
import de.blau.android.tasks.Task;
import de.blau.android.util.EditState;
import de.blau.android.util.FileUtil;
import de.blau.android.util.GeoMath;
import de.blau.android.util.Offset;
import de.blau.android.util.SavingHelper;
import de.blau.android.util.Snack;
import de.blau.android.util.Util;
import de.blau.android.util.collections.MRUList;

/**
 * Logic is the gatekeeper to actual object storage and provides higher level operations.
 * <ul>
 * <li> hold selected objects
 * <li> wrap operations with undo checkpoints
 * <li> hold current mode
 * <li> save and load state
 * </ul>
 * 
 * @author mb
 */
public class Logic {

	private static final String DEBUG_TAG = Logic.class.getSimpleName();

	/**
	 * Enums for directions. Used for translation via cursor-pad.
	 */
	public enum CursorPaddirection {
		DIRECTION_LEFT,
		DIRECTION_DOWN,
		DIRECTION_RIGHT,
		DIRECTION_UP
	}

	/**
	 * Enums for zooming.
	 */
	public static final boolean ZOOM_IN = true;

	public static final boolean ZOOM_OUT = false;

	/**
	 * Minimum width of the viewBox for showing the tolerance. When the viewBox is wider, no element selection is
	 * possible.
	 */
	private static final int TOLERANCE_MIN_VIEWBOX_WIDTH = 40000*2;

	/**
	 * In MODE_EDIT this value is used for the padding of the display border.
	 */
	private static final int PADDING_ON_BORDER_TOUCH = 5;

	/**
	 * In MODE_EDIT, when the user moves a node {@link PADDING_ON_BORDER_TOUCH} pixel to a border, the map will be
	 * translated by this factor.
	 */
	private static final double BORDER_TOCH_TRANSLATION_FACTOR = 0.02;

	/**
	 * Translation factor used by cursor-pad.
	 */
	private static final float TRANSLATION_FACTOR = 0.15f;

	/**
	 * Global factor for all nodes and lines.
	 */
	public static final float STROKE_FACTOR = 100000;
	
	/**
	 * Filename of file containing the currently edit state
	 */
	private static final String EDITSTATE_FILENAME = "edit.state";
	
	/** Sorter instance for sorting nodes by distance */
	private static final DistanceSorter<OsmElement, Node> nodeSorter = new DistanceSorter<OsmElement, Node>();
	/** Sorter instance for sorting ways by distance */
	private static final DistanceSorter<Way, Way> waySorter = new DistanceSorter<Way, Way>();

	/**
	 * maximum number of nodes in a way for it still to be moveable, arbitrary number for now
	 */
	private static final int MAX_NODES_FOR_MOVE = 100;
	
	/**
	 * Stores the {@link Preferences} as soon as they are available.
	 */
	private Preferences prefs;


	/**
	 * The user-selected node.
	 */
	private List<Node> selectedNodes;

	/**
	 * The user-selected way.
	 */
	private List<Way> selectedWays;
	
	/**
	 * The user-selected relation.
	 */
	private List<Relation> selectedRelations;
	
	/* The following are lists because elements could be add multiple times
	 * adding them once per selected relation and the same for deletion avoids 
	 * having to maintain a count 
	 */
	/**
	 * ways belonging to a selected relation
	 */
	private List<Way> selectedRelationWays = null;
	
	/**
	 * nodes belonging to a selected relation
	 */
	private List<Node> selectedRelationNodes = null;
	
	/**
	 * relations belonging to a selected relation 
	 */
	private List<Relation> selectedRelationRelations = null;
	
	
	/**
	 * The user-selected bug.
	 */
	private Task selectedBug;

	private final static int MRULIST_SIZE = 10;
	/**
	 * last changeset comment
	 */
	private MRUList<String> lastComments = new MRUList<String>(MRULIST_SIZE);
	
	/**
	 * last changeset source
	 */
	private MRUList<String> lastSources = new MRUList<String>(MRULIST_SIZE);
	
	
	/**
	 * Are we currently dragging a node?
	 * Set by {@link #handleTouchEventDown(float, float)}
	 */
	private boolean draggingNode = false;
	
	
	/**
	 * Are we currently dragging a way?
	 * Set by {@link #handleTouchEventDown(float, float)}
	 */
	private boolean draggingWay = false;
	private int startLat;
	private int startLon;
	private float startY;
	private float startX;
	private float centroidY;
	private float centroidX;

	/**
	 * Are we currently dragging a handle?
	 */
	private boolean draggingHandle = false;
	private Node handleNode = null;
	
	/**
	 * 
	 */
	private boolean rotatingWay = false;
	
	/**
	 * Current mode.
	 */
	private Mode mode;

	/**
	 * Screen locked or not
	 */
	private boolean locked;
	
	/**
	 * The viewBox for the map. All changes on this Object are made in here or in {@link Tracker}.
	 */
	private final BoundingBox viewBox;

	/**
	 * An instance of the map. Value set by Main via constructor.
	 */
	private Map map;

	private Set<OsmElement> clickableElements;
	
	/**
	 * add relations to result of clicks/touches
	 */
	private boolean returnRelations = true;
	
	/**
	 * The currently selected handle to be dragged to create a new node in a way.
	 */
	private Handle selectedHandle = null;

	/**
	 * Filter to apply if any
	 */
	private Filter filter = null;

	/**
	 * Should we show a warning if hidden/filtered objects are manipulated not persisted in edit state for now
	 */
	private boolean attachedObjectWarning = true;

	/**
	 * Initiate all needed values. Starts Tracker and delegate the first values for the map.
	 * 
	 */
	Logic() {
		viewBox = getDelegator().getLastBox();
		mode = Mode.MODE_EASYEDIT;
		setLocked(true);
	}

	/**
	 * Set all {@link Preferences} and delegates them to {@link Tracker} and {@link Map}. The AntiAlias-Flag will be set
	 * to {@link Paints}. Map gets repainted.
	 * 
	 * @param prefs the new Preferences.
	 */
	void setPrefs(final Preferences prefs) {
		this.prefs = prefs;
		DataStyle.switchTo(prefs.getMapProfile());
	}

	
	/**
	 * Informs the current drawing profile of the user preferences affecting
	 * drawing, the current screen properties, and clears the way cache.
	 */
	public void updateProfile() {
		DataStyle.switchTo(prefs.getMapProfile());
		DataStyle.updateStrokes(strokeWidth(viewBox.getWidth()));
		DataStyle.setAntiAliasing(prefs.isAntiAliasingEnabled());
		// zap the cached style for all ways
		for (Way w:getDelegator().getCurrentStorage().getWays()) {
			w.setFeatureProfile(null);
		}
	}
	
	/**
	 * @return locked status
	 */
	public boolean isLocked() {
		return locked;
	}

	/**
	 * @param locked set locked status 
	 */
	public void setLocked(boolean locked) {
		this.locked = locked;
	}

	/**
	 * Sets new mode.
	 * If the new mode is different from the current one,
	 * all selected Elements will be nulled, the Map gets repainted,
	 * and the action bar will be reset.
	 * 
	 * @param main instance of main that is calling this
	 * @param mode mode to set
	 */
	public void setMode(@NotNull final Main main, @NotNull final Mode mode) {
		Log.d(DEBUG_TAG,"current mode " + this.mode + " new mode " + mode);
		if (this.mode == mode) {
			return;
		}
		Mode oldMode = this.mode; 
		this.mode = mode;
		Main.onEditModeChanged();
		setSelectedBug(null);
		deselectAll();
		oldMode.teardown(main, this);
		mode.setup(main, this);
		invalidateMap();
	}

	/**
	 * Returns the current mode that the program is in.
	 * @return the mode
	 */
	public Mode getMode() {
		return mode;
	}
	
	/**
	 * Checks for changes in the API-Storage.
	 * 
	 * @return {@link StorageDelegator#hasChanges()}
	 */
	public boolean hasChanges() {
		return getDelegator().hasChanges();
	}
	
	/**
	 * Get the current undo instance.
	 * For immediate use only - DO NOT CACHE THIS.
	 * 
	 * @return the UndoStorage, allowing operations like creation of checkpoints and undo/redo.  
	 */
	public UndoStorage getUndo() {
		return getDelegator().getUndo();
	}

	/**
	 * Wrapper to ensure the dirty flag is set 
	 * @return checkpoint name or null if none available
	 */
	@Nullable
	public String undo() {
		String name = getDelegator().getUndo().undo();
		getDelegator().dirty();
		return name;
	}
	
	/**
	 * Wrapper to ensure the dirty flag is set 
	 * @return checkpoint name or null if none available
	 */
	@Nullable
	public String redo() {
		String name = getDelegator().getUndo().redo();
		getDelegator().dirty();
		return name;
	}
	
	/**
	 * Checks if the viewBox is close enough to the viewBox to be in the ability to edit something.
	 * 
	 * @return true, if viewBox' width is smaller than {@link #TOLERANCE_MIN_VIEWBOX_WIDTH}.
	 */
	public boolean isInEditZoomRange() {
		return (viewBox.getWidth() < TOLERANCE_MIN_VIEWBOX_WIDTH) && (viewBox.getHeight() < TOLERANCE_MIN_VIEWBOX_WIDTH) && !map.tooManyNodes();
	}

	/**
	 * Translates the viewBox into the given direction by {@link #TRANSLATION_FACTOR} and sets GPS-Following to false.
	 * Map will be repainted.
	 * 
	 * @param direction the direction of the translation.
	 */
	public void translate(final CursorPaddirection direction) {
		float translation = viewBox.getWidth() * TRANSLATION_FACTOR;
		try {
			switch (direction) {
			case DIRECTION_LEFT:
				viewBox.translate(map, (int) -translation, 0);
				break;
			case DIRECTION_DOWN:
				viewBox.translate(map, 0, -(GeoMath.latE7ToMercatorE7(viewBox.getTop())-(int)(viewBox.getBottomMercator()*1E7D))); 
				break;
			case DIRECTION_RIGHT:
				viewBox.translate(map, (int) translation, 0);
				break;
			case DIRECTION_UP:
				viewBox.translate(map, 0, GeoMath.latE7ToMercatorE7(viewBox.getTop())-(int)(viewBox.getBottomMercator()*1E7D)); 
				break;
			}
		} catch (OsmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		invalidateMap();
	}
	
	/**
	 * Test if the requested zoom operation can be performed.
	 * 
	 * @param zoomIn The zoom operation: ZOOM_IN or ZOOM_OUT.
	 * @return true if the zoom operation can be performed, false if it can't.
	 */
	public boolean canZoom(final boolean zoomIn) {
		return zoomIn ? viewBox.canZoomIn() : viewBox.canZoomOut();
	}
	
	/**
	 * Zooms in or out. Checks if the new viewBox is close enough for editing
	 * and sends this value to map. Strokes will be updated and map will be repainted.
	 * 
	 * @param zoomIn
	 *            true for zooming in.
	 */
	public void zoom(final boolean zoomIn) {
		if (zoomIn) {
			viewBox.zoomIn();
		} else {
			viewBox.zoomOut();
		}
		DataStyle.updateStrokes(strokeWidth(viewBox.getWidth()));
		if (rotatingWay) {
			showCrosshairsForCentroid();
		}
		map.postInvalidate();
	}
	
	/**
	 * Zooms the map in or out by the given factor and updates the map view after zooming.
	 * 
	 * @param zoomFactor The factor to zoom by, negative values zoom out, positive zooms in.
	 */
	public void zoom(final float zoomFactor) {
		viewBox.zoom(zoomFactor);
		DataStyle.updateStrokes(strokeWidth(viewBox.getWidth()));
		if (rotatingWay) {
			showCrosshairsForCentroid();
		}
		map.postInvalidate();
	}
	
	/**
	 * Set the zoom to a specific tile zoom level.
	 * 
	 * @param z The TMS zoom level to zoom to (from 0 for the whole world to about 19 for small areas).
	 */
	public void setZoom(Map map,int z) {
		viewBox.setZoom(map, z);
		if (rotatingWay) {
			showCrosshairsForCentroid();
		}
	}

	/**
	 * Return a stroke width value that increases with zoom and is capped at a configurable value
	 * 
	 * @param width   screenwidth in 10e7 deg.
	 * @return 		  stroke width
	 */
	private float strokeWidth(long width){
		// prefs may not have been initialized
		if (prefs != null ) return Math.min(prefs.getMaxStrokeWidth(), STROKE_FACTOR / width);
		return STROKE_FACTOR / width;
	}
	
	/**
	 * Create an undo checkpoint using a resource string as the name, records imagery used at the same time
	 * 
	 * @param activity that we were called from for access to the resources, if null we will use the resources from App
	 * @param stringId the resource id of the string representing the checkpoint name
	 */
	private void createCheckpoint(@Nullable Activity activity, int stringId) {
		Resources r = activity != null ? activity.getResources() : App.resources();
		boolean firstCheckpoint = !getDelegator().getUndo().canUndo();
		getDelegator().getUndo().createCheckpoint(r.getString(stringId));
		getDelegator().recordImagery(map);
		if (firstCheckpoint && activity instanceof AppCompatActivity) {
			((AppCompatActivity)activity).supportInvalidateOptionsMenu();
		}
	}
	
	/**
	 * Remove an undo checkpoint using a resource string as the name
	 * 
	 * @param activity that we were called from for access to the resources, if null we will use the resources from App
	 * @param stringId the resource id of the string representing the checkpoint name
	 */
	private void removeCheckpoint(@Nullable Activity activity, int stringId) {
		Resources r = activity != null ? activity.getResources() : App.resources();
		getDelegator().getUndo().removeCheckpoint(r.getString(stringId));
	}
	
	/**
	 * Delegates the setting of the Tag-list to {@link StorageDelegator}.
	 * All existing tags will be replaced.
	 * 
	 * @param activity activity we were called from
	 * @param e element to change the tags on
	 * @param tags Tag-List to be set.
	 * @return false if the element wasn't in storage and the tags were not applied
	 * @throws OsmIllegalOperationException if the e isn't in storage
	 */
	public synchronized void setTags(@Nullable Activity activity, @NonNull final OsmElement e, @Nullable final java.util.Map<String, String> tags) throws OsmIllegalOperationException { 
		setTags(activity, e.getName(), e.getOsmId(), tags);
	}
	
	/**
	 * Delegates the setting of the Tag-list to {@link StorageDelegator}.
	 * All existing tags will be replaced.
	 * 
	 * @param activity activity we were called from
	 * @param type type of the element for the Tag-list.
	 * @param osmId OSM-ID of the element.
	 * @param tags Tag-List to be set.
	 * @return false if no element exists for the given osmId/type.
	 */
	public synchronized void setTags(@Nullable Activity activity, final String type, final long osmId, @Nullable final java.util.Map<String, String> tags) throws OsmIllegalOperationException {
		OsmElement osmElement = getDelegator().getOsmElement(type, osmId);
		if (osmElement == null) {
			Log.e(DEBUG_TAG, "Attempted to setTags on a non-existing element " + type + " #" + osmId);
			throw new OsmIllegalOperationException("Element " + osmElement + " not in storage");
		} else {
			createCheckpoint(activity, R.string.undo_action_set_tags);
			getDelegator().setTags(osmElement, tags);
		}
	}
	
	/**
	 * Delegates the setting of the Tag-list to {@link StorageDelegator}.
	 * All existing tags will be replaced.
	 * 
	 * @param activity activity we were called from
	 * @param type type of the element for the Tag-list.
	 * @param osmId OSM-ID of the element.
	 * @param tags Tag-List to be set.
	 * @return false if no element exists for the given osmId/type.
	 */
	public synchronized boolean updateParentRelations(@Nullable Activity activity, final String type, final long osmId, final HashMap<Long, String> parents) {
		OsmElement osmElement = getDelegator().getOsmElement(type, osmId);
		if (osmElement == null) {
			Log.e(DEBUG_TAG, "Attempted to update relations on a non-existing element");
			return false;
		} else {
			createCheckpoint(activity, R.string.undo_action_update_relations);
			getDelegator().updateParentRelations(osmElement, parents);	
			return true;
		}
	}
	
	/**
	 * Updates the list of members in the selected relation.
	 * Actual work is delegated out to {@link StorageDelegator}.
	 * 
	 * @param activity activity we were called from
	 * @param osmId The OSM ID of the relation to change.
	 * @param members The new list of members to set for the given relation.
	 */
	public synchronized boolean updateRelation(@Nullable Activity activity, long osmId, ArrayList<RelationMemberDescription> members) {
		OsmElement osmElement = getDelegator().getOsmElement(Relation.NAME, osmId);
		if (osmElement == null) {
			Log.e(DEBUG_TAG, "Attempted to update non-existing relation #" + osmId);
			return false;
		} else {
			createCheckpoint(activity, R.string.undo_action_update_relations);
			getDelegator().updateRelation((Relation)osmElement, members);	
			return true;
		}
	}

	/**
	 * Prepares the screen for an empty map. Strokes will be updated and map will be repainted.
	 * 
	 * @param activity activity that called us
	 * @param box the new empty map-box. Don't mess up with the viewBox!
	 */
	void newEmptyMap(@NotNull FragmentActivity activity, @NotNull BoundingBox box) {
		Log.d(DEBUG_TAG, "newEmptyMap");
		if (box == null) { // probably should do a more general check if the BB is valid
			box = BoundingBox.getMaxMercatorExtent();
		}
		
		// not checking will zap edits, given that this method will only be called when we are not downloading, not a good thing
		if (!getDelegator().isDirty()) {
			getDelegator().reset(false);
			// delegator.setOriginalBox(box); not needed IMHO
		} else {
			//TODO show warning
			Log.e(DEBUG_TAG, "newEmptyMap called on dirty storage");
		}
		// if the map view isn't drawn use an approximation for the aspect ratio of the display ... this is a hack 
		DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
		float ratio = (float)metrics.widthPixels / (float)metrics.heightPixels;
		if (map.getHeight() != 0) {
			ratio = (float) map.getWidth() / map.getHeight();
		}
		viewBox.setBorders(map, box, ratio, false); 
		map.setViewBox(box);
		DataStyle.updateStrokes(strokeWidth(viewBox.getWidth()));
		invalidateMap();
		activity.supportInvalidateOptionsMenu();
	}

	/**
	 * Searches for all Ways and Nodes at x,y plus the shown node-tolerance. Nodes have to lie in the mapBox.
	 * 
	 * @param x display-coordinate.
	 * @param y display-coordinate.
	 * @return a List of all OsmElements (Nodes and Ways) within the tolerance
	 */
	public List<OsmElement> getClickedNodesAndWays(final float x, final float y) {
		ArrayList<OsmElement> result = new ArrayList<OsmElement>();
		result.addAll(getClickedNodes(x, y));
		result.addAll(getClickedWays(x, y));
		if (returnRelations) {
			// add any relations that the elements are members of
			result.addAll(getParentRelations(result));
		}
		return result;
	}
	
	/**
	 * 
	 * @param elements
	 * @return
	 */
	private ArrayList<OsmElement> getParentRelations(ArrayList<OsmElement> elements) {
		ArrayList<OsmElement> relations = new ArrayList<OsmElement>();
		for (OsmElement e: elements) {
			if (e.getParentRelations() != null) {
				for (Relation r: e.getParentRelations()) {
					if (!relations.contains(r)) { // not very efficient
						relations.add(r);
						//FIXME add one level of parent relations of relations
						// we could do this recursively but would need to add loop protection
						if (r.getParentRelations() != null) {
							for (Relation p: r.getParentRelations()) {
								if (!relations.contains(p)) { 
									relations.add(p);
								}
							}
						}
					}
				}
			}
		}
		return relations;
    }

	/**
	 * Returns all ways within way tolerance from the given coordinates, and their distances from them.
	 * 
	 * @param x x display coordinate
	 * @param y y display coordinate
	 * @return a hash map mapping Ways to distances
	 */
	private HashMap<Way, Double> getClickedWaysWithDistances(final float x, final float y) {
		return getClickedWaysWithDistances(true, x, y);
	}
	
	/**
	 * Returns all ways within way tolerance from the given coordinates, and their distances from them.
	 * 
	 * @param includeClosed include closed ways in the result if true
	 * @param x x display coordinate
	 * @param y y display coordinate
	 * @return a hash map mapping Ways to distances
	 */
	private HashMap<Way, Double> getClickedWaysWithDistances(boolean includeClosed, final float x, final float y) {
		HashMap<Way, Double> result = new HashMap<Way, Double>();
		boolean showWayIcons = prefs.getShowWayIcons();

		List<Way> ways = filter != null ? filter.getVisibleWays() : getDelegator().getCurrentStorage().getWays(map.getViewBox());
		
		for (Way way : ways) {
			if (way.isClosed() && !includeClosed) {
				continue;
			}
			boolean added = false;
			List<Node> wayNodes = way.getNodes();

			if (clickableElements != null && !clickableElements.contains(way)) continue;

			double A = 0;
			double Y = 0;
			double X = 0;
			float node1X = Float.MAX_VALUE;
			float node1Y = Float.MAX_VALUE;
			//Iterate over all WayNodes, but not the last one.
			for (int k = 0, wayNodesSize = wayNodes.size(); k < wayNodesSize - 1; ++k) {
				Node node1 = wayNodes.get(k);
				Node node2 = wayNodes.get(k + 1);
				if (node1X == Float.MAX_VALUE) {
					node1X = lonE7ToX(node1.getLon());
					node1Y = latE7ToY(node1.getLat());
				}
				float node2X = lonE7ToX(node2.getLon());
				float node2Y = latE7ToY(node2.getLat());
				
				double distance = isPositionOnLine(x, y, node1X, node1Y, node2X, node2Y);
				if (distance >= 0) {
					result.put(way, distance);
					added = true;
					break;
				}
				// calculations for centroid
				double d = node1X*node2Y - node2X*node1Y;
				A = A + d;
				X = X + (node1X+node2X)*d;
				Y = Y + (node1Y+node2Y)*d;	
				node1X = node2X;
				node1Y = node2Y;
			}
			if (Util.notZero(A) && showWayIcons && !added && way.isClosed() && (way.hasTagKey(Tags.KEY_BUILDING) || way.hasTag(Tags.KEY_INDOOR, Tags.VALUE_ROOM))) {
				Y = Y/(3*A);
				X = X/(3*A);
				double distance =  Math.hypot(x-X, y-Y);
				if (distance < DataStyle.getCurrent().nodeToleranceValue) {
					result.put(way, distance);
				}
			}
		}		
		return result;
	}
	
	/**
	 * The small mid segment 'x' handles that allow dragging to easily add a new node to a way.
	 */
	class Handle {
		float x;
		float y;
		
		Handle(float x, float y) {
			this.x = x;
			this.y = y;
		}
	}
	
	/**
	 * Returns all ways with a mid-way segment handle tolerance from the given coordinates, and their distances from them.
	 * 
	 * @param x x display coordinate
	 * @param y y display coordinate
	 * @return a hash map mapping Ways to distances
	 */
	private Handle getClickedWayHandleWithDistances(final float x, final float y) {
		
		Handle result = null;
		double bestDistance = Double.MAX_VALUE;
		
		for (Way way : getDelegator().getCurrentStorage().getWays()) {
			List<Node> wayNodes = way.getNodes();

			if (clickableElements != null && !clickableElements.contains(way)) continue;

			//Iterate over all WayNodes, but not the last one.
			for (int k = 0, wayNodesSize = wayNodes.size(); k < wayNodesSize - 1; ++k) {
				Node node1 = wayNodes.get(k);
				Node node2 = wayNodes.get(k + 1);
				// TODO only project once per node
				float node1X = lonE7ToX(node1.getLon());
				float node1Y = latE7ToY(node1.getLat());
				float xDelta = lonE7ToX(node2.getLon()) - node1X;
				float yDelta = latE7ToY(node2.getLat()) - node1Y;
				
				float handleX = node1X + xDelta/2; 
				float handleY = node1Y + yDelta/2;

				float differenceX = Math.abs(handleX - x);
				float differenceY = Math.abs(handleY - y);
				
				if ((differenceX > DataStyle.getCurrent().wayToleranceValue) && (differenceY > DataStyle.getCurrent().wayToleranceValue))	continue;
				if (Math.hypot(xDelta,yDelta) <= DataStyle.getCurrent().minLenForHandle) continue;
				
				double dist = Math.hypot(differenceX, differenceY);
				// TODO better choice for tolerance 
				if ((dist <= DataStyle.getCurrent().wayToleranceValue) && (dist < bestDistance)) {
					if (filter != null) {
						if (filter.include(way, isSelected(way))) {
							bestDistance = dist;
							result = new Handle(handleX, handleY);
						}
					} else {
						bestDistance = dist;
						result = new Handle(handleX, handleY);
					}
				}
				
			}
		}	
		return result;
	}
	
	/**
	 * Calculates the on-screen distance between a node and the screen coordinate of a click.
	 * Returns null if the node was outside the click tolerance.
	 * 
	 * @param node the node
	 * @param x the x coordinate of the clicked point
	 * @param y the y coordinate of the clicked point
	 * @return The distance between the clicked point and the node in px if the node was within the tolerance value,
	 *         null otherwise
	 */
	private Double clickDistance(Node node, final float x, final float y) {
		return clickDistance(node, x, y, node.isTagged() ? DataStyle.getCurrent().nodeToleranceValue : DataStyle.getCurrent().wayToleranceValue/2);
	}

	private Double clickDistance(Node node, final float x, final float y, float tolerance) {

		float differenceX = Math.abs(lonE7ToX(node.getLon()) - x);
		float differenceY = Math.abs(latE7ToY(node.getLat()) - y);
		
		if ((differenceX > tolerance) && (differenceY > tolerance))	return null;
		
		double dist = Math.hypot(differenceX, differenceY);
		return (dist > tolerance) ? null : dist;
	}
	
	/**
	 * Returns all nodes within node tolerance from the given coordinates, and their distances from them.
	 * 
	 * @param x x display coordinate
	 * @param y y display coordinate
	 * @param inDownloadOnly if true the node has to be new or in one of the downloaded bounding boxes
	 * @return a hash map mapping Nodes to distances
	 */
	private HashMap<Node, Double> getClickedNodesWithDistances(final float x, final float y, boolean inDownloadOnly) {
		HashMap<Node, Double> result = new HashMap<Node, Double>();
		List<Node> nodes = filter != null ? filter.getVisibleNodes() : getDelegator().getCurrentStorage().getNodes(map.getViewBox());
		
		for (Node node : nodes) {
			if (clickableElements != null && !clickableElements.contains(node)) {
				continue;
			}

			int lat = node.getLat();
			int lon = node.getLon();

			if (!inDownloadOnly || node.getState() != OsmElement.STATE_UNCHANGED || getDelegator().isInDownload(lat, lon)) {
				Double dist = clickDistance(node, x, y);
				if (dist != null) {
					result.put(node, dist);
				}
			}
		}
		return result;
	}
	
	/**
	 * Searches for a Node at x,y plus the shown node-tolerance. The Node has to lay in the mapBox.
	 * 
	 * @param x display-coordinate.
	 * @param y display-coordinate.
	 * @return all nodes within tolerance found in the currentStorage node-list, ordered ascending by distance.
	 */
	public List<OsmElement> getClickedNodes(final float x, final float y) {
		return nodeSorter.sort(getClickedNodesWithDistances(x, y, true));
	}

	/**
	 * Searches for a way end node at x,y plus the shown node-tolerance. The Node has to lay in the mapBox.
	 * 
	 * @param x display-coordinate.
	 * @param y display-coordinate.
	 * @return all end nodes within tolerance found in the currentStorage node-list, ordered ascending by distance.
	 */
	public List<OsmElement> getClickedEndNodes(final float x, final float y) {
		List<OsmElement> result = new ArrayList<OsmElement>();
		List<OsmElement> allNodes = getClickedNodes(x, y);

		for (OsmElement osmElement : allNodes) {
			if (getDelegator().getCurrentStorage().isEndNode((Node) osmElement))
				result.add(osmElement);
		}

		return result;
	}

	/**
	 * Searches for a Node at x,y plus the shown node-tolerance. The Node has to lay in the mapBox.
	 * 
	 * @param x display-coordinate.
	 * @param y display-coordinate.
	 * @return the nearest node found in the current-Storage node-list. null, when no node was found.
	 */
	private Node getClickedNode(final float x, final float y) {
		Node bestNode = null;
		Double bestDistance = Double.MAX_VALUE;
		HashMap<Node, Double> candidates = getClickedNodesWithDistances(x, y, false);
		for (Entry<Node, Double> candidate : candidates.entrySet()) {
			if (candidate.getValue() < bestDistance) {
				bestNode = candidate.getKey();
				bestDistance = candidate.getValue();
			}
		}
		return bestNode;
	}
	

	/**
	 * Returns all ways within click tolerance from the given coordinate 
	 * 
	 * @param x x display-coordinate.
	 * @param y y display-coordinate.
	 * @return the ways
	 */
	private List<Way> getClickedWays(final float x, final float y) {
		return getClickedWays(true, x, y);
	}
	
	/**
	 * Returns all ways within click tolerance from the given coordinate 
	 * 
	 * @param includeClosed include closed ways in the result if true
	 * @param x x display-coordinate.
	 * @param y y display-coordinate.
	 * @return the ways
	 */
	public List<Way> getClickedWays(boolean includeClosed, final float x, final float y) {
		return waySorter.sort(getClickedWaysWithDistances(includeClosed, x, y));
	}
	
	/**
	 * Returns the closest way (within tolerance) to the given coordinates
	 * 
	 * @param x the x display-coordinate.
	 * @param y the y display-coordinate.
	 * @return the closest way, or null if no way is found within the tolerance
	 */
	private Way getClickedWay(final float x, final float y) {
		Way bestWay = null;
		Double bestDistance = Double.MAX_VALUE;
		HashMap<Way, Double> candidates = getClickedWaysWithDistances(x, y);
		for (Entry<Way, Double> candidate : candidates.entrySet()) {
			if (candidate.getValue() < bestDistance) {
				bestWay = candidate.getKey();
				bestDistance = candidate.getValue();
			}
		}
		return bestWay;
	}
	
	/**
	 * Returns a list of all the clickable OSM elements in storage (does not
	 * restrict to the current screen). Before returning the list is "pruned" to
	 * remove any elements on the exclude list.
	 * 
	 * @param excludes The list of OSM elements to exclude from the results.
	 * @return
	 */
	public Set<OsmElement> findClickableElements(List<OsmElement> excludes) {
		Set<OsmElement> result = new HashSet<OsmElement>();
		result.addAll(getDelegator().getCurrentStorage().getNodes());
		result.addAll(getDelegator().getCurrentStorage().getWays());
		for (OsmElement e:excludes)
			result.remove(e);
		return result;
	}
	
	/**
	 * Get a list of all the Ways connected to the given Node.
	 * 
	 * @param node The Node.
	 * @return A list of all Ways connected to the Node.
	 */
	public List<Way> getWaysForNode(final Node node) {
		return getDelegator().getCurrentStorage().getWays(node);
	}
	
	/**
	 * Get a list of all the filtered Ways connected to the given Node.
	 * 
	 * @param node The Node.
	 * @return A list of all Ways connected to the Node.
	 */
	public List<Way> getFilteredWaysForNode(final Node node) {
		List<Way> ways = new ArrayList<Way>();
		for (Way w:getDelegator().getCurrentStorage().getWays(node)) {
			if (getFilter() == null || filter.include(w, false)) {
				ways.add(w);
			}
		}
		return ways;
	}

	/**
	 * Test if the given Node is an end node of a Way. Isolated nodes not part
	 * of a way are not considered an end node.
	 * 
	 * @param node Node to test.
	 * @return true if the Node is an end node of a Way, false otherwise.
	 */
	public boolean isEndNode(final Node node) {
		return getDelegator().getCurrentStorage().isEndNode(node);
	}
	
	/**
	 * Check all nodes in way to see if they are in the downloaded data.
	 * 
	 * @param way the way whose nodes should be checked
	 * @return true if the above is the case
	 */
	public boolean isInDownload(Way way) {
		for (Node n:way.getNodes()) {
			if (!getDelegator().isInDownload(n.getLat(), n.getLon())){
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Check if node is in the downloaded data
	 * 
	 * @param node
	 * @return true if the above is the case
	 */
	private boolean isInDownload(Node n) {
		return getDelegator().isInDownload(n.getLat(), n.getLon());
	}
	
	/**
	 * Handles the event when user begins to touch the display. When the viewBox is close enough for editing and the
	 * user is in edit-mode a touched node will bet set to selected. draggingNode will be set if a node is to be moved.
	 * A eventual movement of this node will be done in {@link #handleTouchEventMove(float, float, float, float, boolean)}.
	 * 
	 * @param x display-coord.
	 * @param y display-coord.
	 */
	synchronized void handleTouchEventDown(Activity activity, final float x, final float y) {
		boolean draggingMultiselect = false;
		if (!isLocked() && isInEditZoomRange() && mode.elementsGeomEditiable()) {
			draggingNode = false;
			draggingWay = false;
			draggingHandle = false;
			if (selectedNodes != null && selectedNodes.size() == 1 && selectedWays == null && clickDistance(selectedNodes.get(0), x, y, prefs.largeDragArea() ? DataStyle.getCurrent().largDragToleranceRadius : DataStyle.getCurrent().nodeToleranceValue) != null) {
				draggingNode = true;
				if (prefs.largeDragArea()) {
					startX = lonE7ToX(selectedNodes.get(0).getLon());
					startY = latE7ToY(selectedNodes.get(0).getLat());
				}
			}
			else {
				if (selectedWays != null && selectedWays.size() == 1 && selectedNodes == null) {
					if (!rotatingWay) {	
						Handle handle = getClickedWayHandleWithDistances(x, y);
						if (handle != null) {
							Log.d(DEBUG_TAG,"start handle drag");
							selectedHandle = handle;
							draggingHandle = true;
						} else {
							Way clickedWay = getClickedWay(x, y);
							if (clickedWay != null && (clickedWay.getOsmId() == selectedWays.get(0).getOsmId())) {
								if (selectedWays.get(0).getNodes().size() <= MAX_NODES_FOR_MOVE) {
									startLat = yToLatE7(y);
									startLon = xToLonE7(x);
									draggingWay = true;
								}
								else {
									Snack.barError(activity, R.string.toast_too_many_nodes_for_move);
								}
							}
						}
					} else {
						startX = x;
						startY = y;
					}
				} else {
					// check for multi-select
					if ((selectedWays != null && selectedWays.size() > 1) ||  (selectedNodes != null && selectedNodes.size() > 1) 
							|| ((selectedWays != null && selectedWays.size() >= 1) && (selectedNodes != null && selectedNodes.size() >= 1))) {
						Log.d(DEBUG_TAG, "Multi select detected");
						boolean foundSelected = false;
						if (selectedWays != null) {
							List<Way> clickedWays = getClickedWays(x, y);
							for (Way w:clickedWays) {
								if (selectedWays.contains(w)) {
									foundSelected = true;
									break;
								}
							}
						}
						if (!foundSelected && selectedNodes != null) {
							List<OsmElement> clickedNodes = getClickedNodes(x,y);
							for (OsmElement n:clickedNodes) {
								if (selectedNodes.contains((Node)n)) {
									foundSelected = true;
									break;
								}
							}
						}
						if (foundSelected) {
							startLat = yToLatE7(y);
							startLon = xToLonE7(x);
							startX = x;
							startY = y;
							draggingMultiselect = true;
							draggingWay = true;
						}
					} else {
						if (rotatingWay) {
							rotatingWay = false;
							hideCrosshairs();
						} 
						// at last really nothing selected or special going on
					}
				}
			}
		} else {
			draggingNode = false;
			draggingWay = false;
			rotatingWay = false;
			draggingHandle = false;
		}
		Log.d(DEBUG_TAG,"handleTouchEventDown creating checkpoints");
		if (draggingNode || draggingWay) {
			if (draggingMultiselect) {
				createCheckpoint(activity, R.string.undo_action_moveobjects);
			} else {
				createCheckpoint(activity, draggingNode ? R.string.undo_action_movenode : R.string.undo_action_moveway);
			}
		} else if (rotatingWay) {
			createCheckpoint(activity, R.string.undo_action_rotateway);
		}
	}

	synchronized void handleTouchEventUp(final float x, final float y) {
		handleNode = null;
		draggingHandle = false;
	}
	
	/**
	 * Calculates the coordinates for the center of the screen and displays a crosshair there. 
	 */
	public void showCrosshairsForCentroid()
	{
		if (selectedWays == null) {
			return;
		}
		synchronized(selectedWays) {
			float centroid[] = centroidXY(map.getWidth(), map.getHeight(), viewBox, selectedWays.get(0));
			if (centroid==null) {
				return;
			}
			centroidX = centroid[0];
			centroidY = centroid[1];
			showCrosshairs(centroidX,centroidY);	
		}
	}
	
	/**
	 * Handles a finger-movement on the touchscreen.
	 * Moves a node when draggingNode was set by {@link #handleTouchEventDown(float, float)}.
	 * Otherwise the movement will be interpreted as map-translation.
	 * Map will be repainted.
	 * 
	 * @param absoluteX The absolute display-coordinate.
	 * @param absoluteY The absolute display-coordinate.
	 * @param relativeX The difference to the last absolute display-coordinate.
	 * @param relativeY The difference to the last absolute display-coordinate.
	 * @throws OsmIllegalOperationException 
	 */
	synchronized void handleTouchEventMove(Main main, final float absoluteX, final float absoluteY, final float relativeX, final float relativeY) {
		if (draggingNode || draggingWay || draggingHandle) {
			int lat;
			int lon;
			// checkpoint created where draggingNode is set
			if ((draggingNode && selectedNodes != null && selectedNodes.size() == 1 && selectedWays == null) || draggingHandle) {
				if (draggingHandle) { // create node only if we are really dragging
					try {
						if (handleNode == null && selectedHandle != null && selectedWays != null) {
							Log.d(DEBUG_TAG,"creating node at handle position");
							handleNode = performAddOnWay(main, selectedWays, selectedHandle.x, selectedHandle.y, true);
							selectedHandle = null;
						}
						if (handleNode != null) {
							setSelectedNode(null); // performAddOnWay sets this, need to undo
							getDelegator().updateLatLon(handleNode, yToLatE7(absoluteY), xToLonE7(absoluteX));
						}
					} catch (OsmIllegalOperationException e) {
						Snack.barError(main, e.getMessage());
						return;
					}
				} else {
					displayAttachedObjectWarning(main, selectedNodes.get(0));
					if (prefs.largeDragArea()) {
						startY = startY + relativeY;
						startX = startX - relativeX;
						lat = yToLatE7(startY);
						lon = xToLonE7(startX);
					} else {
						lat = yToLatE7(absoluteY);
						lon = xToLonE7(absoluteX);
					}
					getDelegator().updateLatLon(selectedNodes.get(0), lat, lon);
				}
			} else { // way dragging and multi-select
				lat = yToLatE7(absoluteY);
				lon = xToLonE7(absoluteX);
				ArrayList<Node> nodes = new ArrayList<Node>();
				if (selectedWays != null && selectedWays.size() > 0) { // shouldn't happen but might be a race condition
					for (Way w:selectedWays) {
						nodes.addAll(w.getNodes());
					}	
				}
				if (selectedNodes != null && selectedNodes.size() > 0) {
					for (Node n:selectedNodes) {
						nodes.add(n);
					}
				}
				
				displayAttachedObjectWarning(main, nodes);

				getDelegator().moveNodes(nodes, lat - startLat, lon - startLon);
				// update 
				startLat = lat;
				startLon = lon;
			}
			translateOnBorderTouch(absoluteX, absoluteY);
			main.easyEditManager.invalidate(); // if we are in an action mode update menubar
		} else if (rotatingWay) {
			
			double aSq = (startY-absoluteY)*(startY-absoluteY) + (startX-absoluteX)*(startX-absoluteX);
			double bSq = (absoluteY-centroidY)*(absoluteY-centroidY) + (absoluteX-centroidX)*(absoluteX-centroidX);
			double cSq = (startY-centroidY)*(startY-centroidY) + (startX-centroidX)*(startX-centroidX);

			double cosAngle = (bSq + cSq -aSq)/(2*Math.sqrt(bSq)*Math.sqrt(cSq)); 

			int direction = 1; // 1 clockwise, -1 anti-clockwise
			// not perfect but works good enough
			if ((startY <= centroidY) && (absoluteY <= centroidY)) {
				direction = (startX > absoluteX) ? -1 : 1;
			} else if ((startX >= centroidX) && (absoluteX >= centroidX)) {
				direction = (startY > absoluteY) ?  -1 : 1;
			}
			else if ((startY >= centroidY) && (absoluteY >= centroidY)) {
				direction = (startX < absoluteX) ? -1 : 1;
			}
			else if ((startX < centroidX) && (absoluteX < centroidX)) {
				direction = (startY < absoluteY) ? -1 : 1;
			}
			else if ((startY < startX) && (absoluteY < absoluteX)) {
				direction = (startY > absoluteY) ? -1: 1;
			}
			else if ((startY >= startX) && (absoluteY >= absoluteX)) {
				direction = (startY < absoluteY) ? -1: 1;			
			}
			
			displayAttachedObjectWarning(main, selectedWays.get(0));
	
			getDelegator().rotateWay(selectedWays.get(0), (float)Math.acos(cosAngle), direction, centroidX, centroidY, map.getWidth(), map.getHeight(), viewBox);
			startY = absoluteY;
			startX = absoluteX;
			main.easyEditManager.invalidate(); // if we are in an action mode update menubar
		} else {
			if (mode == Mode.MODE_ALIGN_BACKGROUND)
				performBackgroundOffset(relativeX, relativeY);
			else
				performTranslation(map, relativeX, relativeY);
		}	
		invalidateMap();
	}

	/**
	 * @return is we should show warnings when filtered attached objects are being changed
	 */
	private boolean showAttachedObjectWarning() {
		return attachedObjectWarning;
	}
	
	/**
	 * Determine if we should show warnings when filtered attached objects are being changed
	 * @param show
	 */
	public void setAttachedObjectWarning(boolean show) {
		attachedObjectWarning = show;
	}

	/**
	 * Puts the editor into the mode where the selected way will be rotated by
	 * the handleTouchEventMove function on move events.
	 * @param on new state
	 */
	public void setRotationMode(boolean on) {
		rotatingWay = on;
	}
	
	/**
	 * Converts screen-coords to gps-coords and delegates translation to {@link BoundingBox#translate(int, int)}.
	 * GPS-Following will be disabled.
	 * 
	 * @param map current map view
	 * @param screenTransX Movement on the screen.
	 * @param screenTransY Movement on the screen.
	 */
	private void performTranslation(Map map, final float screenTransX, final float screenTransY) {
		// Log.d(DEBUG_TAG,"performTranslation " + screenTransX + " " + screenTransY);
		int height = map.getHeight();
		int lon = xToLonE7(screenTransX);
		int lat = yToLatE7(height - screenTransY);
		int relativeLon = lon - viewBox.getLeft();
		int relativeLat = lat - viewBox.getBottom();

		try {
			viewBox.translate(map, relativeLon, relativeLat);
		} catch (OsmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	/**
	 * Converts screen-coords to gps-coords and offests background layer.
	 * 
	 * @param screenTransX Movement on the screen.
	 * @param screenTransY Movement on the screen.
	 */
	private void performBackgroundOffset(final float screenTransX, final float screenTransY) {
		int height = map.getHeight();
		int lon = xToLonE7(screenTransX);
		int lat = yToLatE7(height - screenTransY);
		int relativeLon = lon - viewBox.getLeft();
		int relativeLat = lat - viewBox.getBottom();
		TileLayerServer osmts = map.getOpenStreetMapTilesOverlay().getRendererInfo();
		double lonOffset = 0d;
		double latOffset = 0d;
		Offset o = osmts.getOffset(map.getZoomLevel());
		if (o != null) {
			lonOffset = o.lon;
			latOffset = o.lat;
		}
		osmts.setOffset(map.getZoomLevel(),lonOffset - relativeLon/1E7d, latOffset - relativeLat/1E7d);
	}

	/**
	 * Executes an add-command for x,y. Adds new nodes and ways to storage. When more than one Node were
	 * created/selected then a new way will be created.
	 * 
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param x screen-coordinate
	 * @param y screen-coordinate
	 * @throws OsmIllegalOperationException 
	 */
	public synchronized void performAdd(@Nullable final Activity activity, final float x, final float y) throws OsmIllegalOperationException {
		Log.d(DEBUG_TAG,"performAdd");
		createCheckpoint(activity, R.string.undo_action_add);
		Node nextNode;
		Node lSelectedNode = selectedNodes != null && selectedNodes.size() > 0 ? selectedNodes.get(0) : null;
		Way lSelectedWay = selectedWays != null && selectedWays.size() > 0 ? selectedWays.get(0) : null;

		if (lSelectedNode == null) {
			//This will be the first node.
			lSelectedNode = getClickedNodeOrCreatedWayNode(x, y);
			if (lSelectedNode == null) {
				//A complete new Node...
				int lat = yToLatE7(y);
				int lon = xToLonE7(x);
				lSelectedNode = getDelegator().getFactory().createNodeWithNewId(lat, lon);
				getDelegator().insertElementSafe(lSelectedNode);
				if (!getDelegator().isInDownload(lat, lon)) {
					Log.d(DEBUG_TAG,"Outside of download");
					Snack.barWarningShort(activity, R.string.toast_outside_of_download);
				}
			}
		} else {
			//this is not the first node
			nextNode = getClickedNodeOrCreatedWayNode(x, y);
			if (nextNode == null) {
				//clicked on empty space -> create a new Node
				if (lSelectedWay == null) {
					//This is the second Node, so we create a new Way and add the previous selected node to this way
					lSelectedWay = getDelegator().createAndInsertWay(lSelectedNode);
				}
				int lat = yToLatE7(y);
				int lon = xToLonE7(x);
				lSelectedNode = getDelegator().getFactory().createNodeWithNewId(lat, lon);
				getDelegator().addNodeToWay(lSelectedNode, lSelectedWay);
				getDelegator().insertElementSafe(lSelectedNode);
				if (!getDelegator().isInDownload(lat, lon)) {
					Log.d(DEBUG_TAG,"Outside of download");
					Snack.barWarningShort(activity, R.string.toast_outside_of_download);
				}
			} else {
				//User clicks an existing Node
				if (nextNode == lSelectedNode) {
					//User clicks the last Node -> end here with adding
					removeCheckpoint(activity, R.string.undo_action_add);
					lSelectedNode = null;
					lSelectedWay = null;
				} else {
					//Create a new way with the existing node, which was clicked.
					if (lSelectedWay == null) {
						lSelectedWay = getDelegator().createAndInsertWay(lSelectedNode);
					}
					//Add the new Node.
					getDelegator().addNodeToWay(nextNode, lSelectedWay);
					lSelectedNode = nextNode;
				}
			}
		}
		setSelectedNode(lSelectedNode);
		setSelectedWay(lSelectedWay);
	}
	
	/**
	 * Simplified version of creating a new node that takes geo coords and doesn't try to merge with existing features
	 * @activity activity this was called from, if null no warnings will be displayed
	 * @param lonD
	 * @param latD
	 * @return the create node
	 */
	public synchronized Node performAddNode(@Nullable final Activity activity, Double lonD, Double latD) {
		//A complete new Node...
		Log.d(DEBUG_TAG,"performAddNode");
		createCheckpoint(activity, R.string.undo_action_add);
		int lon = (int)(lonD*1E7D);
		int lat = (int)(latD*1E7D);
		Node newNode = getDelegator().getFactory().createNodeWithNewId(lat, lon);
		getDelegator().insertElementSafe(newNode);
		if (!getDelegator().isInDownload(lat, lon)) {
			// warning toast
			Log.d(DEBUG_TAG,"Outside of download");
			Snack.barWarningShort(activity, R.string.toast_outside_of_download);
		}
		setSelectedNode(newNode);
		return newNode;
	}
	
	/**
	 * Executes an add node operation for x,y but only if on a way. Adds new node to storage and will select it.
	 * 
	 * @param activity activity we were called from
	 * @param ways candidate ways if null all ways will be considered
	 * @param x screen-coordinate
	 * @param y screen-coordinate
	 * @param forceNew ignore nearby existing nodes
	 * @return the new node or null if none was created
	 * @throws OsmIllegalOperationException
	 */
	public synchronized Node performAddOnWay(@Nullable Activity activity, List<Way>ways,final float x, final float y, boolean forceNew) throws OsmIllegalOperationException {
		createCheckpoint(activity, R.string.undo_action_add);
		Node savedSelectedNode = selectedNodes != null && selectedNodes.size() > 0 ? selectedNodes.get(0) : null;
		
		Node newSelectedNode = getClickedNodeOrCreatedWayNode(ways,x, y, forceNew);

		if (newSelectedNode == null) {
			newSelectedNode = savedSelectedNode;
			return null;
		}
			
		setSelectedNode(newSelectedNode);
		return newSelectedNode;
	}
	
	/**
	 * Catches the first node at the given position and delegates the deletion to {@link #delegator}.
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param createCheckpoint create and undo checkpoint if true
	 * @param x screen-coordinate.
	 * @param y screen-coordinate.
	 */
	public synchronized void performEraseNode(@Nullable final FragmentActivity activity, final Node node, boolean createCheckpoint) {
		if (node != null) {
			if (createCheckpoint) {
				createCheckpoint(activity, R.string.undo_action_deletenode);
			}
			displayAttachedObjectWarning(activity, node); // needs to be done before removal
			getDelegator().removeNode(node);
			invalidateMap();
			if (!isInDownload(node)) {
				// warning toast
				Snack.barWarning(activity, R.string.toast_outside_of_download);
			} 
		}
	}

	/**
	 * set new coordinates and center viewbox on them
	 * 
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param node node to set position on
	 * @param lon longitude
	 * @param lat latitude
	 */
	public void performSetPosition(@Nullable final FragmentActivity activity, Node node, double lon, double lat) {
		if (node != null) {
			createCheckpoint(activity, R.string.undo_action_movenode);
			int lonE7 = (int)(lon*1E7d);
			int latE7 = (int)(lat*1E7d);
			getDelegator().updateLatLon(node, latE7, lonE7);
			viewBox.moveTo(map, lonE7, latE7);
			invalidateMap();
			displayAttachedObjectWarning(activity, node);
		}
	}

	/**
	 * Deletes a way.
	 * 
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param way the way to be deleted
	 * @param deleteOrphanNodes if true, way nodes that have no tags and are in no other ways will be deleted too
	 * @param createCheckpoint if true create an undo checkpoint
	 */
	public synchronized void performEraseWay(@Nullable final FragmentActivity activity, final Way way, final boolean deleteOrphanNodes, boolean createCheckpoint) {
		if (createCheckpoint) {
			createCheckpoint(activity, R.string.undo_action_deleteway);
		}
		displayAttachedObjectWarning(activity, way); // needs to be done before removal
		HashSet<Node> nodes = deleteOrphanNodes ? new HashSet<Node>(way.getNodes()) : null;  //  HashSet guarantees uniqueness
		getDelegator().removeWay(way);
		if (deleteOrphanNodes) {
			for (Node node : nodes) {
				if (getWaysForNode(node).isEmpty() && node.getTags().isEmpty()) getDelegator().removeNode(node);
			}
		}
		invalidateMap();
	}

	/**
	 * Catches the first relation at the given position and delegates the deletion to {@link #delegator}.
	 * 
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param createCheckpoint TODO
	 * @param x screen-coordinate.
	 * @param y screen-coordinate.
	 */
	public synchronized void performEraseRelation(@Nullable final FragmentActivity activity, final Relation relation, boolean createCheckpoint) {
		if (relation != null) {
			if (createCheckpoint) {
				createCheckpoint(activity, R.string.undo_action_delete_relation);
			}
			displayAttachedObjectWarning(activity, relation); // needs to be done before removal
			getDelegator().removeRelation(relation);
			invalidateMap();
		}
	}

	/**
	 * Erase a list of objects
	 * 
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param selection objects to delete
	 */
	public synchronized void performEraseMultipleObjects(@Nullable final FragmentActivity activity, ArrayList<OsmElement> selection) {
		// need to make three passes
		createCheckpoint(activity, R.string.undo_action_delete_objects);
		displayAttachedObjectWarning(activity, selection); // needs to be done before removal
		for (OsmElement e:selection) {	
			if (e instanceof Relation && e.getState() != OsmElement.STATE_DELETED) {
				performEraseRelation(activity, (Relation)e, false);
			}
		}	
		for (OsmElement e:selection) {	
			if (e instanceof Way && e.getState() != OsmElement.STATE_DELETED) {
				if (isInDownload((Way)e)) {
					performEraseWay(activity, (Way)e, true, false); // TODO maybe we don't want to delete the nodes
				} else {
					// TODO toast
				}
			}
		}
		for (OsmElement e:selection) {	
			if (e instanceof Node && e.getState() != OsmElement.STATE_DELETED) {
				performEraseNode(activity, (Node)e, false);
			}
		}	
	}
	
	/**
	 * Splits all ways containing the node will be split at the nodes position
	 * 
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param node node to split at
	 */
	public synchronized void performSplit(@Nullable final FragmentActivity activity, final Node node) {
		if (node != null) {
			// setSelectedNode(node);
			createCheckpoint(activity, R.string.undo_action_split_ways);
			displayAttachedObjectWarning(activity, node); // needs to be done before split
			getDelegator().splitAtNode(node);
			invalidateMap();
		}
	}
	
	/**
	 * Splits a way at a given node
	 * 
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param way the way to split
	 * @param node the node at which the way should be split
	 * @return the new way or null if failed
	 */
	public synchronized Way performSplit(@Nullable final FragmentActivity activity, final Way way, final Node node) {
		// setSelectedNode(node);
		createCheckpoint(activity, R.string.undo_action_split_way);
		Way result = getDelegator().splitAtNode(way, node);
		invalidateMap();
		return result;
	}
	
	/**
	 * Split a closed way, needs two nodes
	 * 
	 * @param activity activity we were called fron
	 * @param way
	 * @param node1
	 * @param node2
	 * @return null if split fails, the two ways otherwise
	 */
	public synchronized Way[] performClosedWaySplit(@Nullable Activity activity, Way way, Node node1, Node node2, boolean createPolygons) {
		createCheckpoint(activity, R.string.undo_action_split_way);
		Way[] result = getDelegator().splitAtNodes(way, node1, node2, createPolygons);
		invalidateMap();
		return result;
	}
	
	/**
	 * Merge two ways.
	 * Ways must be valid (i.e. have at least two nodes) and mergeable
	 * (i.e. have a common start/end node).
	 *  
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param mergeInto Way to merge the other way into. This way will be kept.
	 * @param mergeFrom Way to merge into the other. This way will be deleted.
	 * @throws OsmIllegalOperationException 
	 */
	public synchronized boolean performMerge(@Nullable final FragmentActivity activity, Way mergeInto, Way mergeFrom) throws OsmIllegalOperationException {
		createCheckpoint(activity, R.string.undo_action_merge_ways);
		displayAttachedObjectWarning(activity, mergeInto, mergeFrom, true); // needs to be done before merge
		boolean mergeOK = getDelegator().mergeWays(mergeInto, mergeFrom);
		invalidateMap();
		return mergeOK;
	}
	
	/**
	 * Merge a sorted list of ways
	 * 
	 * FIXME if sortedWays does not contain only ways things will crash with a cast exception
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param sortedWays list of ways to be merged
	 * @throws OsmIllegalOperationException 
	 */
	public synchronized boolean performMerge(@Nullable FragmentActivity activity, List<OsmElement> sortedWays) throws OsmIllegalOperationException {
		createCheckpoint(activity, R.string.undo_action_merge_ways);
		displayAttachedObjectWarning(activity, sortedWays, true); // needs to be done before merge
		boolean mergeOK = true;
		Way previousWay = (Way) sortedWays.get(0);
		for (int i=1;i<sortedWays.size();i++) {
			Way nextWay = (Way) sortedWays.get(i);
			if (!getDelegator().mergeWays(previousWay, nextWay)) {
				Log.d(DEBUG_TAG,"ways " + previousWay.getDescription() + " and " + nextWay + " caused a merge conflict"); 
				mergeOK = false; // signal that we had a problem somewhere
			}
			if (previousWay.getState() == OsmElement.STATE_DELETED) {
				previousWay = nextWay;
			}
		}
		return mergeOK;
	}
	
	/**
	 * Orthogonalize a way (aka make angles 90°)
	 * 
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param way way to square
	 */
	public void performOrthogonalize(@Nullable FragmentActivity activity, Way way) {
		if (way != null && way.getNodes().size() < 3) return;
		ArrayList<Way> ways = new ArrayList<Way>(1);
		ways.add(way);
		performOrthogonalize(activity, ways);
	}
	
	/**
	 * Orthogonalize multiple ways at once (aka make angles 90°)
	 * 
	 * FIXME this can take a long time and should likely be run async
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param ways ways to square
	 */
	public synchronized void performOrthogonalize(@Nullable FragmentActivity activity, List<Way> ways) {
		if (ways==null || ways.size()==0) {
			return;
		}
		createCheckpoint(activity, R.string.undo_action_orthogonalize);
		getDelegator().orthogonalizeWay(map, ways);
		invalidateMap();
		if (getFilter() != null && showAttachedObjectWarning()) {
			HashSet<Node> nodes = new HashSet<Node>();
			for (Way w:ways) {
				nodes.addAll(w.getNodes());
			}
			displayAttachedObjectWarning(activity, nodes);
		}
	}

	/**
	 * Replace node in all ways it is a member of with a new node,
	 * leaving node selected, if it already is. Note: relation memberships are not modified
	 * 
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param node node to extract from any ways it is a member of
	 * @return the new way node or null if the node was not a way node
	 */
	public synchronized Node performExtract(@Nullable FragmentActivity activity, final Node node) {
		if (node != null) {
			createCheckpoint(activity, R.string.undo_action_extract_node);
			displayAttachedObjectWarning(activity, node); // this needs to be done -before- we replace the node
			Node newNode = getDelegator().replaceNode(node);
			invalidateMap();
			return newNode;
		}
		return null;
	}
	
	/**
	 * If any ways are close to the node (within the tolerance), return the way.
	 * 
	 * @param nodeToJoin
	 * @return the closest way to the node
	 */
	public OsmElement findJoinableElement(Node nodeToJoin) {
		OsmElement closestElement = null;
		double closestDistance = Double.MAX_VALUE;
		float jx = lonE7ToX(nodeToJoin.getLon());
		float jy = latE7ToY(nodeToJoin.getLat());
		// start by looking for the closest nodes
		for (Node node : getDelegator().getCurrentStorage().getNodes()) {
			if (node != nodeToJoin) {
				Double distance = clickDistance(node, jx, jy);
				if (distance != null && distance < closestDistance && (filter == null || filter.include(node,false))) {
					closestDistance = distance;
					closestElement = node;
				}
			}
		}
		if (closestElement == null) {
			// fall back to closest ways
			for (Way way : getDelegator().getCurrentStorage().getWays()) {
				if (!way.hasNode(nodeToJoin)) {
					List<Node> wayNodes = way.getNodes();
					for (int i = 1, wayNodesSize = wayNodes.size(); i < wayNodesSize; ++i) {
						Node node1 = wayNodes.get(i - 1);
						Node node2 = wayNodes.get(i);
						// TODO only project once per node
						float node1X = lonE7ToX(node1.getLon());
						float node1Y = latE7ToY(node1.getLat());
						float node2X = lonE7ToX( node2.getLon());
						float node2Y = latE7ToY(node2.getLat());
						double distance = isPositionOnLine(jx, jy, node1X, node1Y, node2X, node2Y);
						if (distance >= 0) {
							if (distance < closestDistance && (filter == null || filter.include(way,false))) {
								closestDistance = distance;
								closestElement = way;
							}
						}
					}
				}
			}
		}
		return closestElement;
	}
	
	/**
	 * Join a node to a node or way at the point on the way closest to the node.
	 * 
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param element Node or Way that the node will be joined to.
	 * @param nodeToJoin Node to be joined to the way.
	 * @throws OsmIllegalOperationException 
	 */
	public synchronized boolean performJoin(@Nullable FragmentActivity activity, OsmElement element, Node nodeToJoin) throws OsmIllegalOperationException {
		boolean mergeOK = true;
		if (element instanceof Node) {
			Node node = (Node)element;
			createCheckpoint(activity, R.string.undo_action_join);
			displayAttachedObjectWarning(activity, node,nodeToJoin); // needs to be done before join
			mergeOK = getDelegator().mergeNodes(node, nodeToJoin);
			invalidateMap();
		}
		else if (element instanceof Way) {
			Way way = (Way)element;
			List<Node> wayNodes = way.getNodes();
			for (int i = 1, wayNodesSize = wayNodes.size(); i < wayNodesSize; ++i) {
				Node node1 = wayNodes.get(i - 1);
				Node node2 = wayNodes.get(i);
				float x = lonE7ToX(nodeToJoin.getLon());
				float y = latE7ToY(nodeToJoin.getLat());
				// TODO only project once per node
				float node1X = lonE7ToX(node1.getLon());
				float node1Y = latE7ToY(node1.getLat());
				float node2X = lonE7ToX(node2.getLon());
				float node2Y = latE7ToY(node2.getLat());
				double distance = isPositionOnLine(x, y, node1X, node1Y, node2X, node2Y);
				if (distance >= 0) {
					float[] p = GeoMath.closestPoint(x, y, node1X, node1Y, node2X, node2Y);
					int lat = yToLatE7(p[1]);
					int lon = xToLonE7(p[0]);
					createCheckpoint(activity, R.string.undo_action_join);
					Node node = null;
					if (node == null && lat == node1.getLat() && lon == node1.getLon()) {
						node = node1;
					}
					if (node == null && lat == node2.getLat() && lon == node2.getLon()) {
						node = node2;
					}
					if (node == null) {
						displayAttachedObjectWarning(activity, way,nodeToJoin); // needs to be done before join
						// move the existing node onto the way and insert it into the way
						getDelegator().updateLatLon(nodeToJoin, lat, lon);
						getDelegator().addNodeToWayAfter(node1, nodeToJoin, way);
					} else {
						displayAttachedObjectWarning(activity, node,nodeToJoin); // needs to be done before join
						// merge node into tgtNode
						mergeOK = getDelegator().mergeNodes(node, nodeToJoin);
					}
					invalidateMap();
					break; // need to leave loop !!!
				}
			}
		}
		return mergeOK;
	}
	
	/**
	 * Unjoin ways joined by the given node.
	 * 
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param activity activity this was called from, if null no warnings will be displayed
	 * @param node Node that is joining the ways to be unjoined.
	 */
	public synchronized void performUnjoin(@Nullable FragmentActivity activity,Node node) {
		createCheckpoint(activity, R.string.undo_action_unjoin_ways);
		displayAttachedObjectWarning(activity, node); // needs to be done before unjoin
		getDelegator().unjoinWays(node);
		invalidateMap();
	}
	
	/**
	 * Reverse a way
	 * 
	 * @param activity activity we were called from
	 * @param way the way to reverse
	 * @return true if reverseWay returned true, implying that tags had to be reversed
	 */
	public synchronized boolean performReverse(@Nullable Activity activity, Way way) {
		createCheckpoint(activity, R.string.undo_action_reverse_way);
		boolean hadToReverse = getDelegator().reverseWay(way);
		invalidateMap();
		return hadToReverse;
	}
	
	public synchronized void performAppendStart(Way way, Node node) {
		setSelectedNode(node);
		setSelectedWay(way);
		invalidateMap();
	}
	
	public synchronized void performAppendStart(OsmElement element) {
		Way lSelectedWay = null;
		Node lSelectedNode = null;
		
		if (element != null) {
			if (element instanceof Node) {
				lSelectedNode = (Node) element;
				List<Way> ways = getDelegator().getCurrentStorage().getWays(lSelectedNode);
				// TODO Resolve possible multiple ways that end at the node
				for (Way way : ways) {
					if (way.isEndNode(lSelectedNode)) {
						lSelectedWay = way;
						break;
					}
				}
				if (lSelectedWay == null) {
					lSelectedNode = null;
				}
			}
		}
		performAppendStart(lSelectedWay, lSelectedNode);
	}
	
	/**
	 * 
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param x
	 * @param y
	 * @throws OsmIllegalOperationException
	 */
	public synchronized void performAppendAppend(@Nullable final Activity activity, final float x, final float y) throws OsmIllegalOperationException {
		Log.d(DEBUG_TAG,"performAppendAppend");
		createCheckpoint(activity, R.string.undo_action_append);
		Node lSelectedNode = getSelectedNode();
		Way lSelectedWay = getSelectedWay();

		Node node = getClickedNodeOrCreatedWayNode(x, y);
		if (node == lSelectedNode) {
			lSelectedNode = null;
			lSelectedWay = null;
		} else if (lSelectedWay != null){ // may have been deselected before we got here
			if (node == null) {
				int lat = yToLatE7(y);
				int lon = xToLonE7(x);
				node = getDelegator().getFactory().createNodeWithNewId(lat, lon);
				getDelegator().insertElementSafe(node);
				if (!getDelegator().isInDownload(lat, lon)) {
					// warning toast
					Snack.barWarningShort(activity, R.string.toast_outside_of_download);
				}
			}
			try {
				getDelegator().appendNodeToWay(lSelectedNode, node, lSelectedWay);
			} catch (OsmIllegalOperationException e) {
				getDelegator().removeNode(node);
				throw new OsmIllegalOperationException(e);
			}
			lSelectedNode = node;
		}
		setSelectedNode(lSelectedNode);
		setSelectedWay(lSelectedWay);
		invalidateMap();
	}

	/**
	 * Tries to locate the selected node. If x,y lays on a way, a new node at this location will be created, stored in
	 * storage and returned.
	 * 
	 * @param x the x screen coordinate
	 * @param y the y screen coordinate
	 * @return the selected node or the created node, if x,y lays on a way. Null if any node or way was selected.
	 * @throws OsmIllegalOperationException 
	 */
	private synchronized Node getClickedNodeOrCreatedWayNode(final float x, final float y) throws OsmIllegalOperationException {
		return getClickedNodeOrCreatedWayNode(null,x,y, false);
	}
	
	/**
	 * Tries to locate the selected node. If x,y lays on a way, a new node at this location will be created, stored in
	 * storage and returned.
	 * 
	 * @param ways list of candidate ways or null for all
	 * @param x the x screen coordinate
	 * @param y the y screen coordinate
	 * @param forceNew do not return existing nodes in tolerance range
	 * @return the selected node or the created node, if x,y lays on a way. Null if any node or way was selected.
	 * @throws OsmIllegalOperationException 
	 */
	private synchronized Node getClickedNodeOrCreatedWayNode(List<Way>ways,final float x, final float y, boolean forceNew) throws OsmIllegalOperationException {
		Node node = null;
		if (!forceNew) {
			node = getClickedNode(x, y);
			if (node != null) {
				return node;
			}
		}
		if (ways==null) {
			ways=getDelegator().getCurrentStorage().getWays();
		}
		Node savedNode1 = null;
		Node savedNode2 = null;
		ArrayList<Way> savedWays = new ArrayList<Way>();
		ArrayList<Boolean> savedWaysSameDirection = new ArrayList<Boolean>();
		double savedDistance = Double.MAX_VALUE;
		//create a new node on a way
		for (Way way : ways) {
			if (filter != null) {
				if (!filter.include(way, isSelected(way))) {
					continue;
				}
			} 			
			List<Node> wayNodes = way.getNodes();
			for (int k = 1, wayNodesSize = wayNodes.size(); k < wayNodesSize; ++k) {
				Node node1 = wayNodes.get(k - 1);
				Node node2 = wayNodes.get(k);
				// TODO only project once per node
				float node1X = lonE7ToX(node1.getLon());
				float node1Y = latE7ToY(node1.getLat());
				float node2X = lonE7ToX(node2.getLon());
				float node2Y = latE7ToY(node2.getLat());

				double distance = isPositionOnLine(x, y, node1X, node1Y, node2X, node2Y);
				if (distance >= 0) {
					if ((savedNode1 == null && savedNode2 == null) || distance < savedDistance) {
						savedNode1 = node1;
						savedNode2 = node2;
						savedDistance = distance;
						savedWays.clear();
						savedWays.add(way);
						savedWaysSameDirection.clear();
						savedWaysSameDirection.add(true);
					} else if ((node1==savedNode1 && node2==savedNode2)) { 
						savedWays.add(way);
						savedWaysSameDirection.add(true);
					} else if ((node1==savedNode2 && node2==savedNode1)) {
						savedWays.add(way);
						savedWaysSameDirection.add(false);
					}
				}
			}
		}
		// way(s) found in tolerance range
		if (savedNode1 != null && savedNode2 != null) {		
			node = createNodeOnWay(savedNode1, savedNode2, x, y);
			if (node != null) {
				getDelegator().insertElementSafe(node);
				try {
					for (int i=0;i<savedWays.size();i++) {
						if (savedWaysSameDirection.get(i)) {
							getDelegator().addNodeToWayAfter(savedNode1, node, savedWays.get(i));
						} else {
							getDelegator().addNodeToWayAfter(savedNode2, node, savedWays.get(i));
						}
					}
				} catch (OsmIllegalOperationException e) {
					getDelegator().removeNode(node);
					throw new OsmIllegalOperationException(e);
				}
			}	
		}
		return node;
	}

	/**
	 * Creates a new node at x,y between node1 and node2. When x,y does not lay on the line between node1 and node2 it
	 * will return null.
	 * 
	 * @param node1 the first node
	 * @param node2 the second node
	 * @param x screen coordinate where the new node shall be created.
	 * @param y screen coordinate where the new node shall be created.
	 * @return a new created node at lon/lat corresponding to x,y. When x,y does not lay on the line between node1 and
	 *         node2 it will return null.
	 */
	private synchronized Node createNodeOnWay(final Node node1, final Node node2, final float x, final float y) {
		//Nodes have to be converted to screen-coordinates, due to a better tolerance-check.
		float node1X = lonE7ToX(node1.getLon());
		float node1Y = latE7ToY(node1.getLat());
		float node2X = lonE7ToX(node2.getLon());
		float node2Y = latE7ToY(node2.getLat());

		//At first, we check if the x,y is in the bounding box clamping by node1 and node2.
		if (isPositionOnLine(x, y, node1X, node1Y, node2X, node2Y) >= 0) {
			float[] p = GeoMath.closestPoint(x, y, node1X, node1Y, node2X, node2Y);
			int lat = yToLatE7(p[1]);
			int lon = xToLonE7(p[0]);
			return getDelegator().getFactory().createNodeWithNewId(lat, lon);
		}
		return null;
	}

	/**
	 * Checks if the x,y-position plus the tolerance is on a line between node1(x,y) and node2(x,y).
	 * 
	 * To avoid the typical two time calculation of the distance we actually return it
	 * @param x screen X coordinate of the position
	 * @param y screen Y coordinate of the position
	 * @param node1X screen X coordinate of node1
	 * @param node1Y screen Y coordinate of node1
	 * @param node2X screen X coordinate of node2
	 * @param node2Y screen Y coordinate of node2
	 * @return distance >= 0, when x,y plus way-tolerance lays on the line between node1 and node2.
	 */
	private double isPositionOnLine(final float x, final float y,
			final float node1X, final float node1Y,
			final float node2X, final float node2Y) {
		float tolerance = DataStyle.getCurrent().wayToleranceValue / 2f;
		//noinspection SuspiciousNameCombination
		if (GeoMath.isBetween(x, node1X, node2X, tolerance) && GeoMath.isBetween(y, node1Y, node2Y, tolerance)) {
			double distance = GeoMath.getLineDistance(x, y, node1X, node1Y, node2X, node2Y);
			return distance < tolerance ? distance : -1D;
		}
		return -1D;
	}


	/**
	 * Translates the {@link #viewBox} in the direction of x/y's next border.
	 * 
	 * @param x screen-coordinate
	 * @param y screen-coordinate
	 */
	private void translateOnBorderTouch(final float x, final float y) {
		int translationOnBorderTouch = (int) (viewBox.getWidth() * BORDER_TOCH_TRANSLATION_FACTOR);

		try {
			if (x > map.getWidth() - PADDING_ON_BORDER_TOUCH) {
				viewBox.translate(map, translationOnBorderTouch, 0);
			} else if (x < PADDING_ON_BORDER_TOUCH) {
				viewBox.translate(map, -translationOnBorderTouch, 0);
			}

			if (y > map.getHeight() - PADDING_ON_BORDER_TOUCH) {
				viewBox.translate(map, 0, -translationOnBorderTouch);
			} else if (y < PADDING_ON_BORDER_TOUCH) {
				viewBox.translate(map, 0, translationOnBorderTouch);
			}
		} catch (OsmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Loads the area defined by mapBox from the OSM-Server.
	 * 
	 * @param activity activity this was called from
	 * @param mapBox Box defining the area to be loaded.
	 * @param add if true add this data to existing
	 * @param postLoadHandler handler to execute after successful download
	 */
	public synchronized void downloadBox(final FragmentActivity activity, final BoundingBox mapBox, final boolean add, final PostAsyncActionHandler postLoadHandler) {
		try {
			mapBox.makeValidForApi();
		} catch (OsmException e1) {
			Log.e(DEBUG_TAG,"downloadBox invalid download box");
			ErrorAlert.showDialog(activity,ErrorCodes.INVALID_BOUNDING_BOX);
			return;
		} 
		
		final PostMergeHandler postMerge =  new PostMergeHandler(){

			@Override
			public void handler(OsmElement e) {
				e.hasProblem(activity);
			}
		};
		
		new AsyncTask<Boolean, Void, Integer>() {
			
			@Override
			protected void onPreExecute() {
				Progress.showDialog(activity, Progress.PROGRESS_LOADING);
			}
			
			@Override
			protected Integer doInBackground(Boolean... arg) {
				int result = 0;
				try {
					Server server = prefs.getServer();
					if (server.hasReadOnly()) {
						server.getReadOnlyCapabilities();
						if (!(server.readOnlyApiAvailable() && server.readOnlyReadableDB())) {
							return ErrorCodes.API_OFFLINE;
						}
						// try to get write capabilities in any case FIXME unclear what  we should do if the write server is not available
						server.getCapabilities();
					} else {
						server.getCapabilities();
						if (!(server.apiAvailable() && server.readableDB())) {
							return ErrorCodes.API_OFFLINE;
						}
					}
					final OsmParser osmParser = new OsmParser();
					final InputStream in = prefs.getServer().getStreamForBox(activity, mapBox);
					try {
						long startTime = System.currentTimeMillis();
						osmParser.start(in);
						Log.d(DEBUG_TAG,"downloadBox downloaded and parsed input in " + (System.currentTimeMillis()-startTime) + "ms");
						if (arg[0]) { // incremental load
							if (!getDelegator().mergeData(osmParser.getStorage(),postMerge)) {
								result = ErrorCodes.DATA_CONFLICT;
							} else {
								if (mapBox != null) {
									// if we are simply expanding the area no need keep the old bounding boxes
									List<BoundingBox> origBbs = getDelegator().getBoundingBoxes();
									List<BoundingBox> bbs = new ArrayList<BoundingBox>(origBbs);
									for (BoundingBox bb:bbs) {
										if (mapBox.contains(bb)) {
											origBbs.remove(bb);
										}
									}
									getDelegator().addBoundingBox(mapBox);
								}
							}
						} else { // replace data with new download
							getDelegator().reset(false);
							getDelegator().setCurrentStorage(osmParser.getStorage()); // this sets dirty flag
							if (mapBox != null) {
								Log.d(DEBUG_TAG,"downloadBox setting original bbox");
								getDelegator().setOriginalBox(mapBox);
							}
						}
						Map map = activity instanceof Main ? ((Main)activity).getMap() : null;
						if (map != null) {
							viewBox.setBorders(map, mapBox != null ? mapBox : getDelegator().getLastBox()); // set to current or previous
						}
					} finally {
						SavingHelper.close(in);
					}
				} catch (SAXException e) {
					Log.e(DEBUG_TAG, "downloadBox problem parsing", e);
					Exception ce = e.getException();
					if ((ce instanceof StorageException) && ((StorageException)ce).getCode() == StorageException.OOM) {
						result = ErrorCodes.OUT_OF_MEMORY;
					} else {
						result = ErrorCodes.INVALID_DATA_RECEIVED;
					}
					if (getDelegator().getBoundingBoxes().contains(mapBox)) { // remove if download failed
						getDelegator().deleteBoundingBox(mapBox);
					}
				} catch (ParserConfigurationException e) {
					// crash and burn
					// TODO this seems to happen when the API call returns text from a proxy or similar intermediate network device... need to display what we actually got
					Log.e(DEBUG_TAG, "downloadBox problem parsing", e);
					result = ErrorCodes.INVALID_DATA_RECEIVED;
					if (getDelegator().getBoundingBoxes().contains(mapBox)) { // remove if download failed
						getDelegator().deleteBoundingBox(mapBox);
					}
				} catch (OsmServerException e) {
					result = e.getErrorCode();
					Log.e(DEBUG_TAG, "downloadBox problem downloading", e);
					if (getDelegator().getBoundingBoxes().contains(mapBox)) { // remove if download failed
						getDelegator().deleteBoundingBox(mapBox);
					}
				} catch (IOException e) {
					if (e instanceof SSLProtocolException) {
						result = ErrorCodes.SSL_HANDSHAKE;
					} else {
						result = ErrorCodes.NO_CONNECTION;
					}
					Log.e(DEBUG_TAG, "downloadBox problem downloading", e);
					if (getDelegator().getBoundingBoxes().contains(mapBox)) { // remove if download failed
						getDelegator().deleteBoundingBox(mapBox);
					}
				}
				return result;
			}
			
			@Override
			protected void onPostExecute(Integer result) {	
				Progress.dismissDialog(activity, Progress.PROGRESS_LOADING);

				Map map = activity instanceof Main ? ((Main)activity).getMap() : null;
				if (map != null) {
					try {
						viewBox.setRatio(map, (float)map.getWidth() / (float)map.getHeight());
					} catch (OsmException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				if (result != 0) {
					if (result == ErrorCodes.OUT_OF_MEMORY) {
						System.gc();
						if (getDelegator().isDirty()) {
							result = ErrorCodes.OUT_OF_MEMORY_DIRTY;
						}
					}	
					try {
						if (!activity.isFinishing()) {
							ErrorAlert.showDialog(activity,result);
						}
					} catch (Exception ex) { // now and then this seems to throw a WindowManager.BadTokenException, however report, don't crash
						ACRA.getErrorReporter().putCustomData("CAUSE",ex.getMessage());
						ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
						ACRA.getErrorReporter().handleException(ex);
					}
					if (postLoadHandler != null) {
						postLoadHandler.onError();
					}
				} else {
					if (postLoadHandler != null) {
						postLoadHandler.onSuccess();
					}
				}
				if (map != null) {
					DataStyle.updateStrokes(strokeWidth(viewBox.getWidth()));
					invalidateMap();
				}
				activity.supportInvalidateOptionsMenu();
			}	
		}.execute(add);
	}
	
	/**
	 * Loads the area defined by mapBox from the OSM-Server. Static version for auto download
	 * FIXME try to reduce the code duplication here
	 * @param context android context
	 * @param mapBox Box defining the area to be loaded.
	 * @param add if true add this data to existing
	 * @param auto download is being done automatically, try not mess up/move the display
	 */
	public synchronized void autoDownloadBox(final Context context, final Server server, final BoundingBox mapBox) {
		try {
			mapBox.makeValidForApi();
		} catch (OsmException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} // TODO remove this? and replace with better error messaging
		
		final PostMergeHandler postMerge =  new PostMergeHandler(){

			@Override
			public void handler(OsmElement e) {
				e.hasProblem(context);
			}
		};
		
		new AsyncTask<Void, Void, Integer>() {
			
			@Override
			protected void onPreExecute() {
			}
			
			@Override
			protected Integer doInBackground(Void... arg) {
				int result = 0;
				try {
					final OsmParser osmParser = new OsmParser();
					final InputStream in = server.getStreamForBox(context, mapBox);
					try {
						osmParser.start(in);
						if (!getDelegator().mergeData(osmParser.getStorage(),postMerge)) {
							result = ErrorCodes.DATA_CONFLICT;
						} else {
							if (mapBox != null) {
								// if we are simply expanding the area no need keep the old bounding boxes
								List<BoundingBox> origBbs = getDelegator().getBoundingBoxes();
								if ( origBbs.size() == 1) { // replace original BB if still present
									if (getDelegator().isEmpty()) {
										origBbs.clear();
									}
								}
								List<BoundingBox> bbs = new ArrayList<BoundingBox>(origBbs);
								for (BoundingBox bb:bbs) {
									if (mapBox.contains(bb)) {
										origBbs.remove(bb);
									}
								}
								getDelegator().addBoundingBox(mapBox);
							}
						}
					} finally {
						SavingHelper.close(in);
					}
				} catch (SAXException e) {
					Log.e("Vespucci", "Problem parsing", e);
					Exception ce = e.getException();
					if ((ce instanceof StorageException) && ((StorageException)ce).getCode() == StorageException.OOM) {
						result = ErrorCodes.OUT_OF_MEMORY;
					} else {
						result = ErrorCodes.INVALID_DATA_RECEIVED;
					}
					if (getDelegator().getBoundingBoxes().contains(mapBox)) { // remove if download failed
						getDelegator().deleteBoundingBox(mapBox);
					}
				} catch (ParserConfigurationException e) {
					// crash and burn
					// TODO this seems to happen when the API call returns text from a proxy or similar intermediate network device... need to display what we actually got
					Log.e("Vespucci", "Problem parsing", e);
					result = ErrorCodes.INVALID_DATA_RECEIVED;
					if (getDelegator().getBoundingBoxes().contains(mapBox)) { // remove if download failed
						getDelegator().deleteBoundingBox(mapBox);
					}
				} catch (OsmServerException e) {
					result = e.getErrorCode();
					Log.e("Vespucci", "Problem downloading", e);
					if (getDelegator().getBoundingBoxes().contains(mapBox)) { // remove if download failed
						getDelegator().deleteBoundingBox(mapBox);
					}
				} catch (IOException e) {
					result = ErrorCodes.NO_CONNECTION;
					Log.e("Vespucci", "Problem downloading", e);
					if (getDelegator().getBoundingBoxes().contains(mapBox)) { // remove if download failed
						getDelegator().deleteBoundingBox(mapBox);
					}
				}
				return result;
			}
			
			@Override
			protected void onPostExecute(Integer result) {
			}
		}.execute();
	}

	/**
	 * Calls the actual downloadBox function using the current map view as the
	 * bounding box for the download.
	 * 
	 * @param activity activity this was called from
	 * @param add add if true add this data to existing
	 * @see #downloadBox(activity, BoundingBox, boolean)
	 */
	void downloadCurrent(final FragmentActivity activity, boolean add) {
		Log.d(DEBUG_TAG,"viewBox: " + viewBox.getBottom() + " " + viewBox.getLeft() + " " + viewBox.getTop() + " " + viewBox.getRight());
		downloadBox(activity, viewBox.copy(),add, null);
	}
	
	/**
	 * Re-downloads the same area as last time
	 * 
	 * @param activity activity this was called from
	 * @see #downloadBox(activity, BoundingBox, boolean)
	 */
	void downloadLast(final FragmentActivity activity) {
		getDelegator().reset(false);
		for (BoundingBox box:getDelegator().getBoundingBoxes()) {
			if (box != null && box.isValidForApi()) {
				downloadBox(activity, box, true, null);
			}
		}
	}

	/**
	 * Return a single element from the API, does not merge into storage, synchronous
	 * 
	 * @param activity the activity that called us
	 * @param type type of the element
	 * @param id id of the element
	 * @return element if successful, null if not
	 */
	public synchronized OsmElement getElement(@Nullable final Activity activity, final String type, final long id) {

		class GetElementTask extends AsyncTask<Void, Void, OsmElement> {
			int result = 0;

			@Override
			protected void onPreExecute() {

			}

			@Override
			protected OsmElement doInBackground(Void... arg) {
				OsmElement element = null;
				try {
					final OsmParser osmParser = new OsmParser();
					final InputStream in = prefs.getServer().getStreamForElement(activity, Way.NAME.equals(type)?"full":null, type, id);
					try {
						osmParser.start(in);
						element = osmParser.getStorage().getOsmElement(type, id);
					} finally {
						SavingHelper.close(in);
					}
				} catch (SAXException e) {
					Log.e(DEBUG_TAG, "getElement problem parsing", e);
					Exception ce = e.getException();
					if ((ce instanceof StorageException) && ((StorageException)ce).getCode() == StorageException.OOM) {
						result = ErrorCodes.OUT_OF_MEMORY;
					} else {
						result = ErrorCodes.INVALID_DATA_RECEIVED;
					}
				} catch (ParserConfigurationException e) {
					// crash and burn
					// TODO this seems to happen when the API call returns text from a proxy or similar intermediate network device... need to display what we actually got
					Log.e(DEBUG_TAG, "getElement problem parsing", e);
					result = ErrorCodes.INVALID_DATA_RECEIVED;
				} catch (OsmServerException e) {
					Log.e(DEBUG_TAG, "getElement problem downloading", e);
				} catch (IOException e) {
					result = ErrorCodes.NO_CONNECTION;
					Log.e(DEBUG_TAG, "getElement problem downloading", e);
				}
				return element;
			}

			@Override
			protected void onPostExecute(OsmElement result) {
				// potentially do something if there is an error
			}

		}
		GetElementTask loader = new GetElementTask();
		loader.execute();

		try {
			return loader.get(20, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			return null;
		} catch (ExecutionException e) {
			return null;
		} catch (TimeoutException e) {
			return null;
		}
	}

	 
	 /**
	  * Download a single element from the API and merge
	  * 
	  * @param ctx Android context
	  * @param type type of the element
	  * @param id OSM id of the element
	  * @param relationFull if we are downloading a relation download with full option
	  * @param withParents download parent relations
	  * @param postLoadHandler callback to execute after download completes if null method waits for download to finish
	  * @return an error code 0 for success
	  */
	 public synchronized int downloadElement(@NonNull final Context ctx, @NonNull final String type, final long id, 
			 final boolean relationFull, final boolean withParents,
			 @Nullable final PostAsyncActionHandler postLoadHandler) {
		 class DownLoadElementTask extends AsyncTask<Void, Void, Integer> {
			 @Override
			 protected void onPreExecute() {
			 }

			 @Override
			 protected Integer doInBackground(Void... arg) {
				 int result = 0;
				 try {
					 final Server server = prefs.getServer();
					 final OsmParser osmParser = new OsmParser();
					
					 // TODO this currently does not retrieve ways the node may be a member of
					 // we always retrieve ways with nodes, relations "full" is optional
					 InputStream in = server.getStreamForElement(ctx, (type.equals(Relation.NAME) && relationFull) ||  type.equals(Way.NAME)? "full" : null, type, id);
					 
					 try {
						 osmParser.start(in);
					 } finally {
						 SavingHelper.close(in);
					 }
					 if (withParents) {
						 // optional retrieve relations the element is a member of
						 in = server.getStreamForElement(ctx, "relations", type, id);
						 try {
							 osmParser.start(in);
						 } finally {
							 SavingHelper.close(in);
						 }
					 }
					 
					 if (!getDelegator().mergeData(osmParser.getStorage(),null)) { // FIXME need to check if providing a handler makes sense here
						 result = ErrorCodes.DATA_CONFLICT;
					 } 
				 } catch (SAXException e) {
					 Log.e(DEBUG_TAG, "downloadElement problem parsing", e);
					 Exception ce = e.getException();
					 if ((ce instanceof StorageException) && ((StorageException)ce).getCode() == StorageException.OOM) {
						 result = ErrorCodes.OUT_OF_MEMORY;
					 } else {
						 result = ErrorCodes.INVALID_DATA_RECEIVED;
					 }
				 } catch (ParserConfigurationException e) {
					 // crash and burn
					 // TODO this seems to happen when the API call returns text from a proxy or similar intermediate network device... need to display what we actually got
					 Log.e(DEBUG_TAG, "downloadElement problem parsing", e);
					 result = ErrorCodes.INVALID_DATA_RECEIVED;
				 } catch (OsmServerException e) {
					 Log.e(DEBUG_TAG, "downloadElement problem downloading", e);
				 } catch (IOException e) {
					 result = ErrorCodes.NO_CONNECTION;
					 Log.e(DEBUG_TAG, "downloadElement problem downloading", e);
				 }
				 return result;
			 }

			 @Override
			 protected void onPostExecute(Integer result) {
				if (result == 0) {
					if (postLoadHandler != null) {
						postLoadHandler.onSuccess();
					}
				} else {
					if (postLoadHandler != null) {
						postLoadHandler.onError();
					}
				}
			 }
		 }
		 DownLoadElementTask loader = new DownLoadElementTask();
		 loader.execute();

		 if (postLoadHandler == null) {
			 try {
				 return loader.get(20, TimeUnit.SECONDS);
			 } catch (InterruptedException e) {
				 return -1;
			 } catch (ExecutionException e) {
				 return -1;
			 } catch (TimeoutException e) {
				 return -1;
			 }
		 } else {
			 return 0;
		 }
	 }
	
	/**
	 * Return multiple elements of the same type from the API and merge them in to our data
	 * Since this doesn't return way nodes this method probably doesn't make sense
	 * 
	 * @param type
	 * @param id
	 * @return
	 */
//	void downloadElements(final String type, long[] ids) {
//		
//		class MyTask extends AsyncTask<Void, Void, Integer> {
//			int result = 0;
//			
//			@Override
//			protected void onPreExecute() {
//				Application.mainActivity.showDialog(DialogFactory.PROGRESS_LOADING);
//			}
//			
//			@Override
//			protected Integer doInBackground(Void... arg) {
//				try {
//					final OsmParser osmParser = new OsmParser();
//					final InputStream in = prefs.getServer().getStreamForElements(type, ids);
//					try {
//						osmParser.start(in);
//						if (!getDelegator().mergeData(osmParser.getStorage())) {
//							result = DialogFactory.DATA_CONFLICT;
//						} 
//					} finally {
//						SavingHelper.close(in);
//					}
//				} catch (SAXException e) {
//					Log.e("Vespucci", "Problem parsing", e);
//					Exception ce = e.getException();
//					if ((ce instanceof StorageException) && ((StorageException)ce).getCode() == StorageException.OOM) {
//						result = DialogFactory.OUT_OF_MEMORY;
//					} else {
//						result = DialogFactory.INVALID_DATA_RECEIVED;
//					}
//				} catch (ParserConfigurationException e) {
//					// crash and burn
//					// TODO this seems to happen when the API call returns text from a proxy or similar intermediate network device... need to display what we actually got
//					Log.e("Vespucci", "Problem parsing", e);
//					result = DialogFactory.INVALID_DATA_RECEIVED;
//				} catch (OsmServerException e) {
//					Log.e("Vespucci", "Problem downloading", e);
//				} catch (IOException e) {
//					result = DialogFactory.NO_CONNECTION;
//					Log.e("Vespucci", "Problem downloading", e);
//				}
//				return result;
//			}
//			
//			@Override
//			protected void onPostExecute(Integer result) {
//
//				try {
//					Application.mainActivity.dismissDialog(DialogFactory.PROGRESS_LOADING);
//				} catch (IllegalArgumentException e) {
//					// Avoid crash if dialog is already dismissed
//					Log.d("Logic", "", e);
//				}
//
//				if (result != 0) {
//					if (result == DialogFactory.OUT_OF_MEMORY) {
//						System.gc();
//						if (getDelegator().isDirty()) {
//							result = DialogFactory.OUT_OF_MEMORY_DIRTY;
//						}
//					}	
//					try {
//						if (!Application.mainActivity.isFinishing()) {
//							Application.mainActivity.showDialog(result);
//						}
//					} catch (Exception ex) { // now and then this seems to throw a WindowManager.BadTokenException, however report, don't crash
//						ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
//						ACRA.getErrorReporter().handleException(ex);
//					}
//				}
//				invalidateMap();
//
//				UndoStorage.updateIcon();
//			}
//			
//		};
//		MyTask loader = new MyTask();
//		loader.execute();
//	}
	
	/**
	 * Remove an element if it is deleted on the server
	 * <p>
	 * Element is deleted on server, delete locally but don't upload
	 * A bit iffy because of memberships in other objects
	 * 
	 * @param activity activity we were called from
	 * @param e element to delete
	 */
	public synchronized void updateToDeleted(@Nullable Activity activity, OsmElement e) {
		createCheckpoint(activity, R.string.undo_action_fix_conflict);
		if (e.getName().equals(Node.NAME)) {
			getDelegator().removeNode((Node)e);
		} else if (e.getName().equals(Way.NAME)) {
			getDelegator().removeWay((Way)e);
		} else if (e.getName().equals(Relation.NAME)) {
			getDelegator().removeRelation((Relation)e);
		}
		getDelegator().removeFromUpload(e);
		invalidateMap();		
	}
	
	/**
	 * Read a file in (J)OSM format from device
	 * 
	 * @param activity activity that called this
	 * @param uri uri of file to load
	 * @param add unused currently
	 * @throws FileNotFoundException 
	 */
	public void readOsmFile(@NotNull final FragmentActivity activity, final Uri uri, boolean add) throws FileNotFoundException {
		readOsmFile(activity, uri, add, null);
	}

	/**
	 * Read a file in (J)OSM format from device
	 * 
	 * @param activity activity that called this
	 * @param uri uri of file to load
	 * @param add unused currently
	 * @param postLoad callback to execute once file is loaded
	 * @throws FileNotFoundException
	 */
	public void readOsmFile(@NotNull final FragmentActivity activity, final Uri uri, boolean add, final PostAsyncActionHandler postLoad) throws FileNotFoundException {

		final InputStream is;
		
		if (uri.getScheme().equals("file")) {
			is = new FileInputStream(new File(uri.getPath()));
		} else {
			ContentResolver cr = activity.getContentResolver();
			is = cr.openInputStream(uri);
		}
		readOsmFile(activity, is, add, postLoad);
	}
	
	/**
	 * Read a stream in (J)OSM format
	 * 
	 * @param activity activity that called this
	 * @param is input
	 * @param add unused currently
	 * @param postLoad callback to execute once stream has been loaded
	 * @throws FileNotFoundException
	 */
	public void readOsmFile(@NotNull final FragmentActivity activity, final InputStream is, boolean add, final PostAsyncActionHandler postLoad) {
		
		final Map map = activity instanceof Main ? ((Main)activity).getMap() : null;
		
		new AsyncTask<Boolean, Void, Integer>() {
			
			@Override
			protected void onPreExecute() {
				Progress.showDialog(activity, Progress.PROGRESS_LOADING);
			}
			
			@Override
			protected Integer doInBackground(Boolean... arg) {
				int result = 0;
				try {
					final OsmParser osmParser = new OsmParser();
					osmParser.clearBoundingBoxes(); // this removes the default bounding box
					final InputStream in = new BufferedInputStream(is);
					try {
						osmParser.start(in);
						
						StorageDelegator sd = getDelegator();
						sd.reset(false);
						sd.setCurrentStorage(osmParser.getStorage()); // this sets dirty flag
						sd.fixupApiStorage();
						if (map != null) {
							viewBox.setBorders(map, getDelegator().getLastBox()); // set to current or previous
						}
					} finally {
						SavingHelper.close(in);
					}
				} catch (SAXException e) {
					Log.e(DEBUG_TAG, "Problem parsing", e);
					Exception ce = e.getException();
					if ((ce instanceof StorageException) && ((StorageException)ce).getCode() == StorageException.OOM) {
						result = ErrorCodes.OUT_OF_MEMORY;
					} else {
						result = ErrorCodes.INVALID_DATA_READ;
					}
				} catch (ParserConfigurationException e) {
					// crash and burn
					Log.e(DEBUG_TAG, "Problem parsing", e);
					result = ErrorCodes.INVALID_DATA_READ;
				} catch (IOException e) {
					result = ErrorCodes.NO_CONNECTION;
					Log.e(DEBUG_TAG, "Problem reading", e);
				}
				return result;
			}
			
			@Override
			protected void onPostExecute(Integer result) {
				Progress.dismissDialog(activity, Progress.PROGRESS_LOADING);
				if (map != null) {
					try {
						viewBox.setRatio(map, (float)map.getWidth() / (float)map.getHeight());
					} catch (OsmException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					DataStyle.updateStrokes(strokeWidth(viewBox.getWidth()));
				}
				if (result != 0) {
					if (result == ErrorCodes.OUT_OF_MEMORY) {
						System.gc();
						if (getDelegator().isDirty()) {
							result = ErrorCodes.OUT_OF_MEMORY_DIRTY;
						}
					}
					try {
						if (!activity.isFinishing()) {
							ErrorAlert.showDialog(activity,result);
						}
					} catch (Exception ex) { // now and then this seems to throw a WindowManager.BadTokenException, however report, don't crash
						ACRA.getErrorReporter().putCustomData("CAUSE",ex.getMessage());
						ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
						ACRA.getErrorReporter().handleException(ex);
					}
					if (postLoad != null) {
						postLoad.onError();
					}
				} else {
					if (postLoad != null) {
						postLoad.onSuccess();
					}
				}

				invalidateMap();
				activity.supportInvalidateOptionsMenu();
			}
			
		}.execute(add);
	}

	/**
	 * Write data to a file in (J)OSM compatible format, 
	 * if fileName contains directories these are created, otherwise it is stored in the standard public dir
	 * 
	 * @param fileName path of the file to save to
	 * @param postSaveHandler if not null executes code after saving
	 */
	public void writeOsmFile(@NotNull final FragmentActivity activity, @NonNull final String fileName, @Nullable final PostAsyncActionHandler postSaveHandler) {
		
		new AsyncTask<Void, Void, Integer>() {
			
			@Override
			protected void onPreExecute() {
				Progress.showDialog(activity, Progress.PROGRESS_SAVING);
			}
			
			@Override
			protected Integer doInBackground(Void... arg) {
				int result = 0;
				try {
					File outfile = new File(fileName);
					String parent = outfile.getParent();
					if (parent == null) { // no directory specified, save to standard location
						outfile = new File(FileUtil.getPublicDirectory(), fileName);
					} else { // ensure directory exists
						File outdir = new File(parent);
						//noinspection ResultOfMethodCallIgnored
						outdir.mkdirs();
						if (!outdir.isDirectory()) {
							throw new IOException("Unable to create directory " + outdir.getPath());
						}
					}
					Log.d(DEBUG_TAG,"Saving to " + outfile.getPath());
					FileOutputStream fout = null;
					OutputStream out = null;
					try {
						fout = new FileOutputStream(outfile);
						out = new BufferedOutputStream(fout);
						getDelegator().save(out);
					} catch (IllegalArgumentException e) {
						result = ErrorCodes.FILE_WRITE_FAILED;
						Log.e(DEBUG_TAG, "Problem writing", e);
					} catch (IllegalStateException e) {
						result = ErrorCodes.FILE_WRITE_FAILED;
						Log.e(DEBUG_TAG, "Problem writing", e);
					} catch (XmlPullParserException e) {
						result = ErrorCodes.FILE_WRITE_FAILED;
						Log.e(DEBUG_TAG, "Problem writing", e);
					} finally {
						SavingHelper.close(out);
						SavingHelper.close(fout);
					}
				} catch (IOException e) {
					result = ErrorCodes.FILE_WRITE_FAILED;
					Log.e(DEBUG_TAG, "Problem writing", e);
				}
				return result;
			}
			
			@Override
			protected void onPostExecute(Integer result) {
				Progress.dismissDialog(activity, Progress.PROGRESS_SAVING);
				Map map = activity instanceof Main ? ((Main)activity).getMap() : null;
				if (map != null) {
					try {
						viewBox.setRatio(map, (float)map.getWidth() / (float)map.getHeight());
					} catch (OsmException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				if (result != 0) {
					if (result == ErrorCodes.OUT_OF_MEMORY) {
						System.gc();
						if (getDelegator().isDirty()) {
							result = ErrorCodes.OUT_OF_MEMORY_DIRTY;
						}
					}
					if (!activity.isFinishing()) {
						ErrorAlert.showDialog(activity,result);
					}
					if (postSaveHandler != null) {
						postSaveHandler.onError();
					}
				} else {
					if (postSaveHandler != null) {
						postSaveHandler.onSuccess();
					}
				}
			}			
		}.execute();
	}

	/**
	 * Saves to a file (synchronously)
	 * 
	 * @param activity activity that we were called from
	 */
	synchronized void save(final Activity activity) {
		try {
			getDelegator().writeToFile(activity);
			App.getTaskStorage().writeToFile(activity);
		} catch (IOException e) {
			Log.e(DEBUG_TAG, "Problem saving", e);
		}
	}
		
	/**
	 * Saves to a file (asynchronously)
	 * 
	 * @param activity activity that we were called from
	 */
	void saveAsync(final Activity activity) {
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				save(activity);
				// the disadvantage of saving async is that something might have
				// changed during the write .... so we force the dirty flags on
				getDelegator().dirty();
				App.getTaskStorage().setDirty();
				return null;
			}
		}.execute();
	}
	
	/**
	 * Saves the current editing state (selected objects, editing mode, etc) to file.
	 */
	void saveEditingState(Main main) {
		TileLayerServer osmts = map.getOpenStreetMapTilesOverlay().getRendererInfo();
		EditState editState = new EditState(main, this, osmts, main.getImageFileName(), viewBox,  main.getFollowGPS());
		new SavingHelper<EditState>().save(main,EDITSTATE_FILENAME, editState, false);	
	}
	
	/**
	 * Loads the current editing state (selected objects, editing mode, etc) from file.
	 * 
	 * @param main instance of main to setup
	 * @param setViewBox set the view box if true
	 */
	void loadEditingState(Main main, boolean setViewBox) {
		EditState editState = new SavingHelper<EditState>().load(main,EDITSTATE_FILENAME, false);
		if(editState != null) { // 
			editState.setOffset(map.getOpenStreetMapTilesOverlay().getRendererInfo());
			editState.setMiscState(main, this);
			editState.setSelected(main, this);
			if (setViewBox) {
				editState.setViewBox(this,map);
			}
		}
	}

	/**
	 * Loads data from a file in the background.
	 * 
	 * @param context 
	 */
	void loadStateFromFile(@NonNull final FragmentActivity activity, final PostAsyncActionHandler postLoad) {
		
		final int READ_FAILED = 0;
		final int READ_OK = 1;
		final int READ_BACKUP = 2;

		final Map map = activity instanceof Main ? ((Main)activity).getMap() : null;
		
		AsyncTask<Void, Void, Integer> loader = new AsyncTask<Void, Void, Integer>() {
					
			final AlertDialog progress = ProgressDialog.get(activity, Progress.PROGRESS_LOADING);
			
			@Override
			protected void onPreExecute() {
				progress.show();
				Log.d(DEBUG_TAG, "loadFromFile onPreExecute");
			}
			
			@Override
			protected Integer doInBackground(Void... v) {
				if (getDelegator().readFromFile(activity)) {
					if (map != null) {
						viewBox.setBorders(map, getDelegator().getLastBox());
					}
					return Integer.valueOf(READ_OK);
				} else if (getDelegator().readFromFile(activity,StorageDelegator.FILENAME + ".backup")) {
					getDelegator().dirty(); // we need to overwrite the saved state asap
					if (map != null) {
						viewBox.setBorders(map, getDelegator().getLastBox());
					}
					return Integer.valueOf(READ_BACKUP);
				}
				return Integer.valueOf(READ_FAILED);
			}
			
			@Override
			protected void onPostExecute(Integer result) {
				Log.d(DEBUG_TAG, "loadFromFile onPostExecute");
				try {
					progress.dismiss();
				} catch (Exception ex) {
					Log.e(DEBUG_TAG, "loadFromFile dismiss dialog failed with " + ex);
				}
				if (result.intValue() != READ_FAILED) {
					Log.d(DEBUG_TAG, "loadfromFile: File read correctly");
					if (map != null) {
						try {
							viewBox.setRatio(map, (float)map.getWidth() / (float)map.getHeight());
						} catch (Exception e) {
							// invalid dimensions of similar error
							try {
								viewBox.setBorders(map, new BoundingBox(-180.0,-GeoMath.MAX_LAT,180.0,GeoMath.MAX_LAT));
							} catch (OsmException e1) {
								// Can't happen?
								e1.printStackTrace();
							}
						}
						DataStyle.updateStrokes(STROKE_FACTOR / viewBox.getWidth()); // safety measure if not done in loadEiditngState
						loadEditingState((Main) activity, true);
					}

					if (postLoad != null) {
						postLoad.onSuccess();
					}
					if (map != null) {
						invalidateMap();
					}
					// this updates the Undo icon if present
					activity.supportInvalidateOptionsMenu();
					if (result.intValue() == READ_BACKUP) { 
						Snack.barError(activity, R.string.toast_used_backup);
					}
				}
				else {
					Log.d(DEBUG_TAG, "loadfromFile: File read failed");
					Intent intent = new Intent(activity, BoxPicker.class);
					activity.startActivityForResult(intent, Main.REQUEST_BOUNDING_BOX);
					
					Snack.barError(activity, R.string.toast_state_file_failed);
					if (postLoad != null) {
						postLoad.onError();
					}
				}
			}
		};
		loader.execute();
	}
	
	/**
	 * Loads the saved task state from a file in the background.
	 * 
	 * @param activity the activity calling this method 
	 */
	void loadBugsFromFile(@NonNull final Activity activity, final PostAsyncActionHandler postLoad) {
		
		final int READ_FAILED = 0;
		final int READ_OK = 1;
		final int READ_BACKUP = 2;

		AsyncTask<Void, Void, Integer> loader = new AsyncTask<Void, Void, Integer>() {
			
			@Override
			protected void onPreExecute() {
				Log.d(DEBUG_TAG, "loadBugsFromFile onPreExecute");
			}
			
			@Override
			protected Integer doInBackground(Void... v) {
				if (App.getTaskStorage().readFromFile(activity)) {
					// viewBox.setBorders(getDelegator().getLastBox());
					return Integer.valueOf(READ_OK);
				} 
				return Integer.valueOf(READ_FAILED);
			}
			
			@Override
			protected void onPostExecute(Integer result) {
				Log.d(DEBUG_TAG, "loadBugsFromFile onPostExecute");
				if (result.intValue() != READ_FAILED) {
					Log.d(DEBUG_TAG, "loadBugsfromFile: File read correctly");
					
					// FIXME if no bbox exists from data, ty to use one from bugs
					if (postLoad != null) {
						postLoad.onSuccess();
					}
					if (result.intValue() == READ_BACKUP) { 
						Snack.barError(activity, R.string.toast_used_bug_backup);
					}
				}
				else {
					Log.d(DEBUG_TAG, "loadBugsfromFile: File read failed");
					if (postLoad != null) {
						postLoad.onError();
					}
				}
			}
		};
		loader.execute();
	}
	
	/**
	 * Loads data from a file
	 * 
	 */
	public void syncLoadFromFile(@NonNull FragmentActivity activity) {

		final int READ_FAILED = 0;
		final int READ_OK = 1;
		final int READ_BACKUP = 2;

		int result = READ_FAILED;

		Map map = activity instanceof Main ? ((Main)activity).getMap() : null;
		Progress.showDialog(activity, Progress.PROGRESS_LOADING);

		if (getDelegator().readFromFile(activity)) {
			viewBox.setBorders(map, getDelegator().getLastBox());
			result = READ_OK;
		} 

		Progress.dismissDialog(activity, Progress.PROGRESS_LOADING);
		if (result != READ_FAILED) {
			Log.d(DEBUG_TAG, "syncLoadfromFile: File read correctly");
			if (map != null) {
				try {
					viewBox.setRatio(map, (float)map.getWidth() / (float)map.getHeight());
				} catch (Exception e) {
					// invalid dimensions of similar error
					try {
						viewBox.setBorders(map, new BoundingBox(-180.0,-GeoMath.MAX_LAT,180.0,GeoMath.MAX_LAT));
					} catch (OsmException e1) {
						// Can't happen?
						e1.printStackTrace();
					}
				}
				DataStyle.updateStrokes(STROKE_FACTOR / viewBox.getWidth());
				loadEditingState((Main) activity, true);
			}
		
			if (map != null) {
				invalidateMap();
			}
			activity.supportInvalidateOptionsMenu();
			if (result == READ_BACKUP) {
				Snack.barError(activity, R.string.toast_used_backup);
			}
		}
		else {
			Log.d(DEBUG_TAG, "syncLoadfromFile: File read failed");
			Snack.barError(activity, R.string.toast_state_file_failed);
		}
	}

	/**
	 * Uploads to the server in the background.
	 * 
	 * @param activity Activity this is called from
	 * @param comment Changeset comment.
	 * @param source The changeset source tag to add.
	 * @param closeChangeset Whether to close the changeset after upload or not.
	 */
	public void upload(@NonNull final FragmentActivity activity, final String comment, final String source, final boolean closeChangeset) {
		final String PROGRESS_TAG = "data";
		final Server server = prefs.getServer();
		new AsyncTask<Void, Void, UploadResult>() {
			
			@Override
			protected void onPreExecute() {
				Progress.showDialog(activity, Progress.PROGRESS_UPLOADING, PROGRESS_TAG);
				lastComments.push(comment);
				lastSources.push(source);
			}
			
			@Override
			protected UploadResult doInBackground(Void... params) {
				UploadResult result = new UploadResult();
				try {
					server.getCapabilities(); // update status
					if (!(server.apiAvailable() && server.writableDB())) {
						result.error =  ErrorCodes.API_OFFLINE;
						return result;
					}
					getDelegator().uploadToServer(server, comment, source, closeChangeset);
				} catch (final MalformedURLException e) {
					Log.e(DEBUG_TAG, "", e);
					ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
					ACRA.getErrorReporter().handleException(e);
				} catch (final ProtocolException e) {
					Log.e(DEBUG_TAG, "", e);
					ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
					ACRA.getErrorReporter().handleException(e);
				} catch (final OsmServerException e) {
					result.httpError = e.getErrorCode();
					result.message = e.getMessageWithDescription();
					switch (e.getErrorCode()) {
					case HttpURLConnection.HTTP_FORBIDDEN:
						result.error = ErrorCodes.FORBIDDEN;
						break;
					case HttpURLConnection.HTTP_UNAUTHORIZED:
						result.error = ErrorCodes.INVALID_LOGIN;
						break;
					case HttpURLConnection.HTTP_GONE:
					case HttpURLConnection.HTTP_CONFLICT:
					case HttpURLConnection.HTTP_PRECON_FAILED:
						result.error = ErrorCodes.UPLOAD_CONFLICT;
						result.elementType = e.getElementType();
						result.osmId = e.getElementId();
						break;
					case HttpURLConnection.HTTP_BAD_REQUEST:
						result.error = ErrorCodes.BAD_REQUEST;
						result.message = e.getMessage();
						break;
					case HttpURLConnection.HTTP_NOT_FOUND:
					case HttpURLConnection.HTTP_INTERNAL_ERROR:
					case HttpURLConnection.HTTP_BAD_GATEWAY:
					case HttpURLConnection.HTTP_UNAVAILABLE:
						result.error = ErrorCodes.UPLOAD_PROBLEM;
						break;
					//TODO: implement other state handling
					default:
						Log.e(DEBUG_TAG, "", e);
						ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
						ACRA.getErrorReporter().handleException(e);
						break;
					}
				} catch (final IOException e) {
					result.error = ErrorCodes.NO_CONNECTION;
					Log.e(DEBUG_TAG, "", e);
				} catch (final NullPointerException e) {
					Log.e(DEBUG_TAG, "", e);
					ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
					ACRA.getErrorReporter().handleException(e);
				}
				return result;
			}
			
			@Override
			protected void onPostExecute(UploadResult result) {
				Progress.dismissDialog(activity, Progress.PROGRESS_UPLOADING, PROGRESS_TAG);
				if (result.error == 0) {
					save(activity); // save now to avoid problems if it doesn't succeed later on, FIXME async or sync
					Snack.barInfo(activity, R.string.toast_upload_success);
					getDelegator().clearUndo(); // only clear on successful upload
					activity.supportInvalidateOptionsMenu();
				}
				activity.getCurrentFocus().invalidate();
				if (!activity.isFinishing()) {
					if (result.error == ErrorCodes.UPLOAD_CONFLICT) {
						if (result.osmId > 0) {
							UploadConflict.showDialog(activity, result);
						} else {
							Log.e(DEBUG_TAG, "No OSM element found for conflict");
							ErrorAlert.showDialog(activity,ErrorCodes.UPLOAD_PROBLEM);
						}
					} else if (result.error == ErrorCodes.INVALID_LOGIN) {
						InvalidLogin.showDialog(activity);
					} else if (result.error == ErrorCodes.FORBIDDEN) {
						ForbiddenLogin.showDialog(activity,result.message);
					} else if (result.error == ErrorCodes.BAD_REQUEST) {
						ErrorAlert.showDialog(activity,result.error,result.message);
					} else if (result.error != 0) {
						ErrorAlert.showDialog(activity,result.error);
					}
				}
			}			
		}.execute();
	}
	
	

	/**
	 * Uploads a GPS track to the server.
	 * 
	 * @param track the track to upload
	 * @param description a description of the track sent to the server
	 * @param tags the tags to apply to the GPS track (comma delimeted)
	 * @param visibility the track visibility, one of the following: private, public, trackable, identifiable
	 */
	public void uploadTrack(@NonNull final FragmentActivity activity, final Track track, final String description, final String tags, final Visibility visibility) {
		final Server server = prefs.getServer();
		new AsyncTask<Void, Void, Integer>() {
			
			@Override
			protected void onPreExecute() {
				Progress.showDialog(activity, Progress.PROGRESS_UPLOADING);
			}
			
			@Override
			protected Integer doInBackground(Void... params) {
				int result = 0;
				try {
					server.uploadTrack(track, description, tags, visibility);
				} catch (final MalformedURLException e) {
					Log.e(DEBUG_TAG, "", e);
					ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
					ACRA.getErrorReporter().handleException(e);
				} catch (final ProtocolException e) {
					Log.e(DEBUG_TAG, "", e);
					ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
					ACRA.getErrorReporter().handleException(e);
				} catch (final OsmServerException e) {
					switch (e.getErrorCode()) { //FIXME use the same mechanics as for data uoload
					case HttpURLConnection.HTTP_FORBIDDEN: 
					case HttpURLConnection.HTTP_UNAUTHORIZED:
						result = ErrorCodes.INVALID_LOGIN;
						break;
					case HttpURLConnection.HTTP_BAD_REQUEST:
					case HttpURLConnection.HTTP_PRECON_FAILED:
					case HttpURLConnection.HTTP_CONFLICT:
						result = ErrorCodes.UPLOAD_PROBLEM;
						break;
					case HttpURLConnection.HTTP_NOT_FOUND:
					case HttpURLConnection.HTTP_GONE:
					case HttpURLConnection.HTTP_INTERNAL_ERROR:
					case HttpURLConnection.HTTP_BAD_GATEWAY:
					case HttpURLConnection.HTTP_UNAVAILABLE:
						result = ErrorCodes.UPLOAD_PROBLEM;
						break;
					//TODO: implement other state handling
					default:
						Log.e(DEBUG_TAG, "", e);
						ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
						ACRA.getErrorReporter().handleException(e);
						break;
					}
				} catch (final IOException e) {
					result = ErrorCodes.NO_CONNECTION;
					Log.e(DEBUG_TAG, "", e);
				} catch (final NullPointerException e) {
					Log.e(DEBUG_TAG, "", e);
					ACRA.getErrorReporter().putCustomData("STATUS","NOCRASH");
					ACRA.getErrorReporter().handleException(e);
				} catch (IllegalArgumentException e) {
					result = ErrorCodes.UPLOAD_PROBLEM;
				} catch (IllegalStateException e) {
					result = ErrorCodes.UPLOAD_PROBLEM;
				} catch (XmlPullParserException e) {
					result = ErrorCodes.UPLOAD_PROBLEM;
				}
				return result;
			}
			
			@Override
			protected void onPostExecute(Integer result) {
				Progress.dismissDialog(activity, Progress.PROGRESS_UPLOADING);
				if (result == 0) {
					Snack.barInfo(activity, R.string.toast_upload_success);
				}
				activity.getCurrentFocus().invalidate();
				if (result != 0) {
					if (!activity.isFinishing()) {
						if (result == ErrorCodes.INVALID_LOGIN) {
							InvalidLogin.showDialog(activity);
						} else { 
							ErrorAlert.showDialog(activity,result);
						}
					}
				}
			}
			
		}.execute();
	}
	
	/**
	 * Show a toast indicating how many unread mails are on the server
	 * 
	 * @param activity activity calling this method
	 */
	public void checkForMail(final FragmentActivity activity) {
		final Server server = prefs.getServer();
		new AsyncTask<Void, Void, Integer>() {
			
			@Override
			protected void onPreExecute() {
			}
			
			@Override
			protected Integer doInBackground(Void... params) {
				int result = 0;
				
				UserDetails userDetails = server.getUserDetails();
				if (userDetails != null) {
					result = userDetails.unread;
				}
				return result;
			}
			
			@Override
			protected void onPostExecute(Integer result) {
				if (result > 0) {
					try {
						if (activity != null) {
							Snack.barInfo(activity, result == 1 ? activity.getResources().getString(R.string.toast_one_unread_mail) : activity.getResources().getString(R.string.toast_unread_mail, result),
									R.string.read, new View.OnClickListener() {
								@Override
								public void onClick(View v) {	
									try {
										activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Urls.OSM_LOGIN)));
									} catch (Exception ex) {
										//never crash
										Log.e(DEBUG_TAG,"Linking to the OSM login page failed " + ex.getMessage());
									}
								}
							});
						}
					} catch (java.util.IllegalFormatFlagsException iffex) {
						// do nothing ... this is stop bugs in the Android format parsing crashing the upload, happens at least with the PL string
					}
				}
			}	
		}.execute();
	}
	
	/**
	 * Make a new bug at the given screen X/Y coordinates.
	 * 
	 * @param x The screen X-coordinate of the bug.
	 * @param y The screen Y-coordinate of the bug.
	 * @return The new bug, which must have a comment added before it can be submitted to OSB.
	 */
	public Note makeNewBug(final float x, final float y) {
		int lat = yToLatE7(y);
		int lon = xToLonE7(x);
		return new Note(lat, lon);
	}
	
	/**
	 * Setter to a) set the internal value and b) push the value to {@link #map}.
	 * @param selectedNode node to select
	 */
	public synchronized void setSelectedNode(final Node selectedNode) {
		if (selectedNode != null) { // always restart
			selectedNodes = new LinkedList<Node>();
			selectedNodes.add(selectedNode);
		} else {
			selectedNodes = null;
		}
		map.setSelectedNodes(selectedNodes);
		resetFilterCache();
	}
	
	/**
	 * Add nodes to the internal list
	 * @param selectedNode node to add to selection
	 */
	public synchronized void addSelectedNode(final Node selectedNode) {
		if (selectedNodes == null) {
			setSelectedNode(selectedNode);
		} else {
			if (!selectedNodes.contains(selectedNode)) {
				selectedNodes.add(selectedNode);
			}
		}
		resetFilterCache();
	}
	
	/**
	 * De-select a node
	 * @param node node to remove from selection
	 */
	public synchronized void removeSelectedNode(Node node) {
		if (selectedNodes != null) {
			selectedNodes.remove(node);
			if (selectedNodes.size() == 0) {
				selectedNodes = null;
			}
			resetFilterCache();
		}
	}
	
	/**
	 * Setter to a) set the internal value and b) push the value to {@link #map}.
	 * @param selectedWay way to select
	 */
	public synchronized void setSelectedWay(final Way selectedWay) {
		if (selectedWay != null) {  // always restart
			selectedWays = new LinkedList<Way>();
			selectedWays.add(selectedWay);
		} else {
			selectedWays = null;
		}
		map.setSelectedWays(selectedWays);
		resetFilterCache();
	}
	
	/**
	 * Adds the given way to the list of currently selected ways.
	 * @param selectedWay way to add to selection
	 */
	public synchronized void addSelectedWay(final Way selectedWay) {
		if (selectedWays == null) {
			setSelectedWay(selectedWay);
		} else {
			if (!selectedWays.contains(selectedWay)) {
				selectedWays.add(selectedWay);
			}
		}
		resetFilterCache();
	}
	
	/**
	 * Removes the given way from the list of currently selected ways.
	 * @param way way to de-select
	 */
	public synchronized void removeSelectedWay(Way way) {
		if (selectedWays != null) {
			selectedWays.remove(way);
			if (selectedWays.size() == 0) {
				selectedWays = null;
			}
			resetFilterCache();
		}
	}
	
	/**
	 * Setter to a) set the internal value and b) push the value to {@link #map}.
	 * @param selectedRelation relation to select
	 */
	public synchronized void setSelectedRelation(final Relation selectedRelation) {
		if (selectedRelation != null) {  // always restart
			selectedRelations = new LinkedList<Relation>();
			selectedRelations.add(selectedRelation);
		} else {
			selectedRelations = null;
		}
		if (selectedRelation != null) {
			selectRelation(selectedRelation);
		}
		resetFilterCache();
	}
	
	/**
	 * De-select the relation
	 * @param relation relation to remove from selection
	 */
	public synchronized void removeSelectedRelation(Relation relation) {
		if (selectedRelations != null) {
			selectedRelations.remove(relation);
			if (selectedRelations.size() == 0) {
				selectedRelations = null;
			}
			resetFilterCache();
		}
	}
	
	/**
	 * Adds the given relation to the list of currently selected relations.
	 * @param selectedRelation relation to add to selection
	 */
	public synchronized void addSelectedRelation(final Relation selectedRelation) {
		if (selectedRelations == null) {
			setSelectedRelation(selectedRelation);
		} else {
			if (!selectedRelations.contains(selectedRelation)) {
				selectedRelations.add(selectedRelation);
			}
		}
		resetFilterCache();
	}
	
	/**
	 * Helper to clear the current, if any, filter cache
	 */
	private void resetFilterCache() {
		if (filter != null) {
			filter.clear();
		}
	}
	
	/**
	 * Set the currently selected bug.
	 * 
	 * @param bug The selected bug.
	 */
	public synchronized void setSelectedBug(final Task bug) {
		this.selectedBug = bug;
	}

	/**
	 * @return the selectedNode (currently simply the first in the list)
	 */
	public synchronized final Node getSelectedNode() {
		if (selectedNodes != null && selectedNodes.size() > 0) {
			if (!exists(selectedNodes.get(0))) {
				selectedNodes = null; // clear selection if node was deleted
				return null;
			} else {
				return selectedNodes.get(0);
			}
		}
		return null;
	}

	/**
	 * Get list of selected nodes
	 */
	public List<Node> getSelectedNodes() {
		return selectedNodes;
	}

	/**
	 * Return how many nodes are selected
	 */
	public int selectedNodesCount() {
		return selectedNodes == null ? 0 : selectedNodes.size();
	}

	/**
	 * @return the selectedWay (currently simply the first in the list)
	 */
	public synchronized final Way getSelectedWay() {
		if (selectedWays != null && selectedWays.size() > 0) {
			if (!exists(selectedWays.get(0))) {
				selectedWays = null; // clear selection if node was deleted
				return null;
			} else {
				return selectedWays.get(0);
			}
		}
		return null;
	}
	
	/**
	 * Get list of selected ways
	 */
	public List<Way> getSelectedWays() {
		return selectedWays;
	}
	
	/**
	 * Return how many ways are selected
	 */
	public int selectedWaysCount() {
		return selectedWays == null ? 0 : selectedWays.size();
	}
	
	/**
	 * Get list of selected ways
	 */
	public List<Relation> getSelectedRelations() {
		return selectedRelations;
	}
	
	/**
	 * Return how many ways are selected
	 */
	public int selectedRelationsCount() {
		return selectedRelations == null ? 0 : selectedRelations.size();
	}
	
	/**
	 * Check is all selected elements exist, return true if we actually had to remove something
	 */
	boolean resyncSelected() {
		boolean result = false;
		if (selectedNodesCount() > 0) {
			for (Node n:new ArrayList<Node>(selectedNodes)) {
				if (!getDelegator().getCurrentStorage().contains(n)) {
					selectedNodes.remove(n);
					result = true;
				}
			}
		}
		if (selectedWaysCount() > 0) {
			for (Way w:new ArrayList<Way>(selectedWays)) {
				if (!getDelegator().getCurrentStorage().contains(w)) {
					selectedWays.remove(w);
					result = true;
				}
			}
		}
		if (selectedRelationsCount() > 0) {
			for (Relation r:new ArrayList<Relation>(selectedRelations)) {
				if (!getDelegator().getCurrentStorage().contains(r)) {
					selectedRelations.remove(r);
					result = true;
				}
			}
		}
		return result;
	}
	
	/**
	 * @param e
	 * @return true is e is selected
	 */
	public synchronized boolean isSelected(OsmElement e) {
		if (e instanceof Node) {
			return selectedNodes != null && selectedNodes.contains((Node) e);
		} else if (e instanceof Way) {
			return selectedWays != null && selectedWays.contains((Way) e);
		} else if (e instanceof Relation) {
			return selectedRelations != null && selectedRelations.contains((Relation) e);
		}
		return false;
	}
	
	/**
	 * Get the selected bug.
	 * 
	 * @return The selected bug.
	 */
	public synchronized final Task getSelectedBug() {
		return selectedBug;
	}
	
	/**
	 * Get a list of all nodes currently in storage
	 * @return unmodifiable list of all nodes currently loaded
	 */
	public List<Node> getNodes() {
		return getDelegator().getCurrentStorage().getNodes();
	}
	
	/**
	 * Get a list of all nodes contained in bounding box box currently in storage
	 * @param box the bounding box
	 * @return unmodifiable list of all nodes currently loaded contained in box
	 */
	public List<Node> getNodes(BoundingBox box) {
		return getDelegator().getCurrentStorage().getNodes(box);
	}
	
	/**
	 * Get a list of all modified (created, modified, deleted) nodes currently in storage
	 * @return all modified nodes currently loaded
	 */
	public List<Node> getModifiedNodes() {
		return getDelegator().getApiStorage().getNodes();
	}
	
	/**
	 * Get a list of all  nodes contained in bounding box box currently in storage
	 * @param box the bounding box
	 * @return all modified nodes currently loaded contained in box
	 */
	public List<Node> getModifiedNodes(BoundingBox box) {
		return getDelegator().getApiStorage().getNodes(box);
	}
	
	/**
	 * Get a list of all ways currently in storage
	 * @return unmodifiable list of all ways currently loaded
	 */
	public List<Way> getWays() {
		return getDelegator().getCurrentStorage().getWays();
	}
	
	/**
	 * Get a list of all ways contained in or possibly intersecting the bounding box box currently in storage
	 * @param box the bounding box
	 * @return unmodifiable list of all nodes currently loaded contained in box
	 */
	public List<Way> getWays(BoundingBox box) {
		return getDelegator().getCurrentStorage().getWays(box);
	}
	
	/**
	 * Get a list of all modified (created, modified, deleted) ways currently in storage
	 * @return unmodifiable list of all modified ways currently loaded
	 */
	public List<Way> getModifiedWays() {
		return getDelegator().getApiStorage().getWays();
	}
	
	/**
	 * Get a list of all relations currently in storage
	 * @return unmodifiable list of all relations currently loaded
	 */
	public List<Relation> getRelations() {
		return getDelegator().getCurrentStorage().getRelations();
	}
	
	/**
	 * Get a list of all modified (created, modified, deleted) relations currently in storage
	 * @return unmodifiable list of all modified relations currently loaded
	 */
	public List<Relation> getModifiedRelations() {
		return getDelegator().getApiStorage().getRelations();
	}
	
	/**
	 * Will be called when the screen orientation was changed.
	 * 
	 * @param map the new Map-Instance. Be aware: The View-dimensions are not yet set...
	 */
	public void setMap(Map map) {
		this.map = map;
		map.setDelegator(getDelegator());
		map.setViewBox(viewBox);
		setSelectedBug(null);
		setSelectedNode(null);
		setSelectedWay(null);
		setSelectedRelation(null);
		invalidateMap();
	}
	
	/**
	 * Getter for testing
	 * @return map object
	 */
	public Map getMap() {
		return map;
	}
	
	/**
	 * Convenience method to invalidate the map 
	 */
	void invalidateMap() {
		if (map != null) {
			map.invalidate();
		}
	}
	
	/**
	 * @return a list of all pending changes to upload
	 */
	public List<String> getPendingChanges(final Context aCaller) {
		return getDelegator().listChanges(aCaller.getResources());
	}

	/**
	 * Sets the set of elements that can currently be clicked.
	 * <ul>
	 * <li>If set to null, the map will use default behaviour.</li>
	 * <li>If set to a non-null value, the map will highlight only elements in the list.</li>
	 * </ul>
	 * @param clickable a set of elements to which highlighting should be limited, or null to remove the limitation
	 */
	public synchronized void setClickableElements(Set<OsmElement> clickable) {
		clickableElements = clickable;
	}
	
	/**
	 * Get elements that can currently be clicked 
	 * 
	 * @return the list of clickable elements. May be null, meaning no restrictions on clickable elements
	 */
	@Nullable
	public synchronized Set<OsmElement> getClickableElements() {
		return clickableElements;
	}
	
	/**
	 * Sets if we return relations when touching/clicking.
	 * 
	 * @param on true if we should return relations
	 */
	public void setReturnRelations(boolean on) {
		returnRelations = on;
	}
	
	
	/**
	 * Checks if an element exists, i.e. is in currentStorage
	 * 
	 * @param element the element that is to be checked
	 * @return true if the element exists, false otherwise
	 */
	public boolean exists(OsmElement element) {
		return getDelegator().getCurrentStorage().contains(element);
	}
	
	/**
	 * @return the X coordinate (in pixels) of the given node's position on the
	 *         screen (note that the returned position may be outside of the
	 *         screens bounds).
	 */
	public float getNodeScreenX(Node node) {
		return lonE7ToX(node.getLon());
	}

	/**
	 * @return the Y coordinate (in pixels) of the given node's position on the
	 *         screen (note that the returned position may be outside of the
	 *         screens bounds).
	 */
	public float getNodeScreenY(Node node) {
		return latE7ToY(node.getLat());
	}
	
	/** Helper class for ordering nodes/ways by distance from a click */
	private static class DistanceSorter<OUTTYPE extends OsmElement, T extends OUTTYPE> {
		private Comparator<Entry<T, Double>> comparator =
			new Comparator<Entry<T, Double>>() {
				@Override
				public int compare(Entry<T,Double> lhs, Entry<T,Double> rhs) {
					if (lhs == rhs) return 0;
					if (lhs.getValue() > rhs.getValue()) return 1;
					if (lhs.getValue() < rhs.getValue()) return -1;
					return 0;
				}
			};
		
		/** Takes an element-distance map and returns the elements ordered by distance */
		public ArrayList<OUTTYPE> sort(HashMap<T, Double> input) {
			ArrayList<Entry<T, Double>> entries = new ArrayList<Entry<T,Double>>(input.entrySet());
			Collections.sort(entries, comparator);
			
			ArrayList<OUTTYPE> result = new ArrayList<OUTTYPE>(entries.size());
			for (Entry<T, Double> entry : entries) result.add(entry.getKey());
			return result;			
		}
	}

	/**
	 * Creates a turn restriction relation using the given objects as the members in the relation.
	 * 
	 * @param activity activity we were called from
	 * @param fromWay the way on which turning off of is restricted in some fashion
	 * @param viaElement the "intersection node" at which the turn is restricted
	 * @param toWay the way that the turn restriction prevents turning onto
	 * @param restriction_type the kind of turn which is restricted
	 * @return a relation element for the turn restriction
	 */
	public Relation createRestriction(@Nullable Activity activity, Way fromWay, OsmElement viaElement, Way toWay, String restriction_type) {
		
		createCheckpoint(activity, R.string.undo_action_create_relation);
		Relation restriction = getDelegator().createAndInsertRelation(null);
		SortedMap<String,String> tags = new TreeMap<String,String>();
		tags.put("restriction", restriction_type == null ? "" : restriction_type);
		tags.put("type", "restriction");
		getDelegator().setTags(restriction, tags);
		RelationMember from = new RelationMember("from", fromWay);
		getDelegator().addElementToRelation(from, restriction);
		RelationMember via = new RelationMember("via", viaElement);
		getDelegator().addElementToRelation(via, restriction);
		RelationMember to = new RelationMember("to", toWay);
		getDelegator().addElementToRelation(to, restriction);
		
		return restriction;
	}

	/**
	 * Creates a new relation containing the given members.
	 * 
	 * @param activity activity we were called from
	 * @param type the 'type=*' tag to set on the relation itself
	 * @param members the osm elements to include in the relation
	 * @return the new relation
	 */
	public Relation createRelation(@Nullable Activity activity, String type, List<OsmElement> members ) {
		
		createCheckpoint(activity, R.string.undo_action_create_relation);
		Relation relation = getDelegator().createAndInsertRelation(members);
		SortedMap<String,String> tags = new TreeMap<String,String>();
		if (type != null)
			tags.put("type", type);
		else
			tags.put("type", "");
		getDelegator().setTags(relation, tags);
		return relation;
	}
	
	
	/**
	 * Adds the list of elements to the given relation with an empty role set for each new member.
	 * 
	 * @param activity activity we were called from
	 */
	public void addMembers(@Nullable Activity activity, Relation relation, ArrayList<OsmElement> members) {
		createCheckpoint(activity, R.string.undo_action_update_relations);
		getDelegator().addMembersToRelation(relation, members);
	}
	
	/**
	 * Sets the set of ways that belong to a relation and should be highlighted. 
	 * If set to null, the map will use default behaviour.
	 * If set to a non-null value, the map will highlight only elements in the list.
	 * @param set of elements to which highlighting should be limited, or null to remove the limitation
	 */
	public void setSelectedRelationWays(List<Way> ways) {
		selectedRelationWays = ways;
	}
	
	public void addSelectedRelationWay(Way way) {
		if (selectedRelationWays == null) {
			selectedRelationWays = new LinkedList<Way>();
		}
		selectedRelationWays.add(way);
	}
	
	public void removeSelectedRelationWay(Way way) {
		if (selectedRelationWays != null) {
			selectedRelationWays.remove(way);
		}
	}
	
	public List<Way> getSelectedRelationWays() {
		return selectedRelationWays;
	}

	
	/**
	 * Set relation members to be highlighted
	 * @param r
	 */
	public void selectRelation(Relation r) {
		if (r!=null) {
			for (RelationMember rm : r.getMembers()) {
				OsmElement e = rm.getElement();
				if (e != null) {
					if (e.getName().equals(Way.NAME)) {
						addSelectedRelationWay((Way) e);
					} else if (e.getName().equals(Node.NAME)) {
						addSelectedRelationNode((Node) e);
					} else if (e.getName().equals(Relation.NAME) && (selectedRelationRelations == null || !selectedRelationRelations.contains((Relation)e))) { // break recursion if already selected
						addSelectedRelationRelation((Relation) e);
					} 
				}
			}
		}
	}
	
	/**
	 * Sets the set of nodes that belong to a relation and should be highlighted. 
	 * If set to null, the map will use default behaviour.
	 * If set to a non-null value, the map will highlight only elements in the list.
	 * @param set of elements to which highlighting should be limited, or null to remove the limitation
	 */
	public void setSelectedRelationNodes(List<Node> nodes) {
		selectedRelationNodes = nodes;
	}
	
	public void addSelectedRelationNode(Node node) {
		if (selectedRelationNodes == null) {
			selectedRelationNodes = new LinkedList<Node>();
		}
		selectedRelationNodes.add(node);
	}
	
	public void removeSelectedRelationNode(Node node) {
		if (selectedRelationNodes != null) {
			selectedRelationNodes.remove(node);
		}
	}
	
	public List<Node> getSelectedRelationNodes() {
		return selectedRelationNodes;
	}
	
	/**
	 * Sets the set of relations that belong to a relation and should be highlighted. 
	 * If set to null, the map will use default behaviour.
	 * If set to a non-null value, the map will highlight only elements in the list.
	 * @param set of elements to which highlighting should be limited, or null to remove the limitation
	 */
	public void setSelectedRelationRelations(List<Relation> relations) {
		selectedRelationRelations = relations;
		if (selectedRelationRelations != null) {
			for (Relation r:selectedRelationRelations) {
				selectRelation(r);
			}
		}
	}
	
	public void addSelectedRelationRelation(Relation relation) {
		if (selectedRelationRelations == null) {
			selectedRelationRelations = new LinkedList<Relation>();
		}
		selectedRelationRelations.add(relation);
	}
	
	public void removeSelectedRelationRelation(Relation relation) {
		if (selectedRelationRelations != null) {
			selectedRelationRelations.remove(relation);
		}
	}
	
	public List<Relation> getSelectedRelationRelations() {
		return selectedRelationRelations;
	}
	
	public void deselectAll() {
		setSelectedNode(null);
		setSelectedWay(null);
		setSelectedRelation(null);
		setSelectedRelationNodes(null);
		setSelectedRelationWays(null);
	}
	
	/**
	 * Fixup an object with a version conflict
	 * 
	 * Note: this could do with some more explaining
	 * @param activity activity we were called from
	 * @param newVersion new version to use
	 * @param elementLocal the local instance of the element
	 * @param elementOnServer the remote instance of the element
	 */
	public void fixElementWithConflict(@Nullable Activity activity, long newVersion, OsmElement elementLocal, OsmElement elementOnServer) {
		createCheckpoint(activity, R.string.undo_action_fix_conflict);

		if (elementOnServer == null) { // deleted on server
			if (elementLocal.getState() != OsmElement.STATE_DELETED) { // but not locally	
				// given that the element is deleted on the server we likely need to add it back to ways and relations there too
				if (elementLocal.getName().equals(Node.NAME)) {
					for (Way w:getWaysForNode((Node)elementLocal)) {
						getDelegator().setOsmVersion(w,w.getOsmVersion()+1);
					}
				}
				if (elementLocal.hasParentRelations()) {
					for (Relation r:elementLocal.getParentRelations()) {
						getDelegator().setOsmVersion(r,r.getOsmVersion()+1);
					}
				}
			} else { // deleted locally too
				// note this sets the state to unchanged, but the element
				// isn't referenced anywhere anymore so that doesn't matter
				getDelegator().removeFromUpload(elementLocal); 
				return;
			}
		}
		getDelegator().setOsmVersion(elementLocal,newVersion);
	}

	/**
	 * Displays a crosshair marker on the screen at the coordinates given (in pixels).
	 */
	public void showCrosshairs(float x, float y) {
		map.showCrosshairs(x, y);
		invalidateMap();
	}
	
	/**
	 * Hide a display crosshairs
	 */
	public void hideCrosshairs() {
		map.hideCrosshairs();
	}

    /**
     * Copy element to clipboard
     * @param element element to copy
     */
	public void copyToClipboard(OsmElement element) {
		if (element instanceof Node) {
			getDelegator().copyToClipboard(element, ((Node)element).getLat(), ((Node)element).getLon());
		} else if (element instanceof Way) {
			// use current centroid of way
			int result[] = Logic.centroid(map.getWidth(), map.getHeight(), viewBox,(Way)element);
			if (result != null) {
				getDelegator().copyToClipboard(element, result[0], result[1]);
			} else {
				Log.e(DEBUG_TAG,"centroid of way " + element.getDescription() + " is null");
			}
		}
	}

	/**
	 * Cut element to clipboard
	 * @param activity the activity we were called from
	 * @param element the element to cut
	 */
	public void cutToClipboard(@Nullable Activity activity, OsmElement element) {
		createCheckpoint(activity, R.string.undo_action_cut);
		if (element instanceof Node) {
			getDelegator().cutToClipboard(element, ((Node)element).getLat(), ((Node)element).getLon());
		} else if (element instanceof Way) {
			int result[] = Logic.centroid(map.getWidth(), map.getHeight(), viewBox,(Way)element);
			if (result != null) {
				getDelegator().cutToClipboard(element, result[0], result[1]);
			} else {
				Log.e(DEBUG_TAG,"centroid of way " + element.getDescription() + " is null");
			}
		}
		invalidateMap();
	}

	/**
	 * Paste current contents of the clipboard 
	 * @param activity the activity we were called from
	 * @param x screen x to position the object at
	 * @param y screen y to position the object at 
	 */
	public void pasteFromClipboard(@Nullable Activity activity, float x, float y) {
		createCheckpoint(activity, R.string.undo_action_paste);
		int lat = yToLatE7(y);
		int lon = xToLonE7(x);
		getDelegator().pasteFromClipboard(lat, lon);
	}

	public boolean clipboardIsEmpty() {
		return getDelegator().clipboardIsEmpty();
	}


	/**
	 * calculate the centroid of a way
	 * @param viewvBox 
	 * @param h 
	 * @param w 
	 * @param way
	 * @return  WS84 coordinates of centroid
	 */
	private static int[] centroid(int w, int h, BoundingBox v, final Way way) {
		float XY[] = centroidXY(w,h,v,way);
		if (XY == null) {
			return null;
		}
		int lat = GeoMath.yToLatE7(h, w, v, XY[1]);
		int lon = GeoMath.xToLonE7(w, v, XY[0]);
		return new int[]{lat,lon};
	}


	/**
	 * Calculate the centroid of a way
	 * 
	 * @param viewvBox display bounding box
	 * @param h viewbox height
	 * @param w  viewbox width
	 * @param way way to caculate centroid of
	 * @return screen coordinates of centroid, null if the way has problems and if the way has length or area zero return the coordinates of the first node
	 */
	@Nullable
	private static float[] centroidXY(int w, int h, @NonNull BoundingBox v, @NonNull final Way way) {
		if (way == null || way.nodeCount() == 0) {
			return null;
		}
		// 
		List<Node> vertices = way.getNodes();
		if (way.isClosed()) {
			// see http://paulbourke.net/geometry/polygonmesh/
			double A = 0;
			double Y = 0;
			double X = 0;
			int vs = vertices.size();
			for (int i = 0; i < vs ; i++ ) {
				double x1 = GeoMath.lonE7ToX(w, v, vertices.get(i).getLon());
				double y1 = GeoMath.latE7ToY(h, w, v, vertices.get(i).getLat());
				double x2 = GeoMath.lonE7ToX(w, v, vertices.get((i+1) % vs).getLon());
				double y2 = GeoMath.latE7ToY(h, w, v, vertices.get((i+1) % vs).getLat());
				double d = x1*y2 - x2*y1;
				A = A + d;
				X = X + (x1+x2)*d;
				Y = Y + (y1+y2)*d;
			}
			if (Util.notZero(A)) {
				Y = Y/(3*A);
				X = X/(3*A);
				return new float[]{(float)X, (float)Y};
			} else {
				// area zero -> we can choose any node
				Node n0 = vertices.get(0);
				return new float[]{(float)GeoMath.lonE7ToX(w, v, n0.getLon()),(float)GeoMath.latE7ToY(h, w, v, n0.getLat())};
			}
		} else { //
			double L = 0;
			double Y = 0;
			double X = 0;
			int vs = vertices.size();
			for (int i = 0; i < (vs-1) ; i++ ) {
				double x1 = GeoMath.lonE7ToX(w, v, vertices.get(i).getLon());
				double y1 = GeoMath.latE7ToY(h, w, v, vertices.get(i).getLat());
				double x2 = GeoMath.lonE7ToX(w, v, vertices.get(i+1).getLon());
				double y2 = GeoMath.latE7ToY(h, w, v, vertices.get(i+1).getLat());
				double len = Math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1));
				L = L + len;
				X = X + len * (x1+x2)/2;
				Y = Y + len * (y1+y2)/2;
			}
			if (Util.notZero(L)) {
				Y = Y/L;
				X = X/L;
				return new float[]{(float)X, (float)Y};
			} else {
				// length zero -> we can choose any node
				Node n0 = vertices.get(0);
				return new float[]{(float)GeoMath.lonE7ToX(w, v, n0.getLon()),(float)GeoMath.latE7ToY(h, w, v, n0.getLat())};
			}
		}	
	}
	
	/**
	 * Calculate the centroid of a way
	 * 
	 * @param way way to calculate the centroid of
	 * @return WGS84 coordinates of centroid, null if the way has problems and if the way has length or area zero return the coordinates of the first node
	 */
	@Nullable
	public static double[] centroidLonLat(@NonNull final Way way) {
		if (way == null) {
			return null;
		}
		// 
		List<Node> vertices = way.getNodes();
		if (way.isClosed()) {
			// see http://paulbourke.net/geometry/polygonmesh/
			double A = 0;
			double Y = 0;
			double X = 0;
			int vs = vertices.size();
			for (int i = 0; i < vs ; i++ ) {
				double x1 = vertices.get(i).getLon() / 1E7D;
				double y1 = GeoMath.latE7ToMercator(vertices.get(i).getLat());
				double x2 = vertices.get((i+1) % vs).getLon() / 1E7D;
				double y2 = GeoMath.latE7ToMercator(vertices.get((i+1) % vs).getLat());
				A = A + (x1*y2 - x2*y1);
				X = X + (x1+x2)*(x1*y2-x2*y1);
				Y = Y + (y1+y2)*(x1*y2-x2*y1);
			}
			if (Util.notZero(A)) {
				Y = GeoMath.mercatorToLat(Y/(3*A));
				X = X/(3*A);
				return new double[]{X, Y};
			} else {
				// area zero -> we can choose any node
				Node n0 = vertices.get(0);
				return new double[]{n0.getLon()/1E7D,n0.getLat()/1E7D};
			}
		} else { //
			double L = 0;
			double Y = 0;
			double X = 0;
			int vs = vertices.size();
			for (int i = 0; i < (vs-1) ; i++ ) {
				double x1 = vertices.get(i).getLon() / 1E7D;
				double y1 = GeoMath.latE7ToMercator(vertices.get(i).getLat());
				double x2 = vertices.get(i+1).getLon() / 1E7D;
				double y2 = GeoMath.latE7ToMercator(vertices.get((i+1)).getLat());
				double len = Math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1));
				L = L + len;
				X = X + len * (x1+x2)/2;
				Y = Y + len * (y1+y2)/2;
			}
			if (Util.notZero(L)) {
				Y = GeoMath.mercatorToLat(Y/L);
				X = X/L;
				return new double[]{X, Y};
			} else {
				// length zero -> we can choose any node
				Node n0 = vertices.get(0);
				return new double[]{n0.getLon()/1E7D,n0.getLat()/1E7D};
			}
		}	
	}

	/**
	 * Arrange way points in a circle
	 * 
	 * Note: currently only works if map is present
	 * @param activity this method was called from, if null no warnings will be displayed
	 * @param way way to circulize
	 */
	public void performCirculize(@Nullable FragmentActivity activity, Way way) {
		if (way.getNodes().size() < 3) return;
		createCheckpoint(activity, R.string.undo_action_circulize);
		int[] center = centroid(map.getWidth(), map.getHeight(), viewBox, way);
		if (center != null) {
			getDelegator().circulizeWay(map, center, way);
			invalidateMap();
			displayAttachedObjectWarning(activity, way);
		} else {
			Log.e(DEBUG_TAG,"performCirculize  unable to determin centroid for way " + way.getDescription());
		}
	}
	
	/**
	 * convenience function
	 * @param x
	 * @return
	 */
	public int xToLonE7(float x) {
		return GeoMath.xToLonE7(map.getWidth(), viewBox, x);
	}

	/**
	 * convenience function
	 * @param y
	 * @return
	 */
	public int yToLatE7(float y) {
		return GeoMath.yToLatE7(map.getHeight(), map.getWidth(), viewBox, y);
	}

	/**
	 * convenience function
	 * @param lon
	 * @return
	 */
	public float lonE7ToX(int lon) {
		return 	GeoMath.lonE7ToX(map.getWidth(), viewBox, lon);
	}

	/**
	 * convenience function
	 * @param lat
	 * @return
	 */
	public float latE7ToY(int lat) {
		return 	GeoMath.latE7ToY(map.getHeight(),map.getWidth(), viewBox, lat);
	}

	/**
	 * @return the delegator
	 */
	private static StorageDelegator getDelegator() {
		return App.getDelegator();
	}
	
	public void getDataLock() {
		getDelegator().lock();
	}
	
	public void dataUnlock() {
		getDelegator().unlock();
	}

	/**
	 * @return the viewBox
	 */
	public BoundingBox getViewBox() {
		return viewBox;
	}

	/**
	 * Return the last used comment
	 * @return comment
	 */
	public String getLastComment() {
		return lastComments.last();
	}

	/**
	 * Return the last used comments index 0 is the most recent one
	 * @return ArrayList of the comments
	 */
	public ArrayList<String> getLastComments() {
		return lastComments;
	}

	/**
	 * Set the list of last comments
	 * @param comments
	 */
	public void setLastComments(ArrayList<String> comments) {
		lastComments = new MRUList<String>(comments);
		lastComments.ensureCapacity(MRULIST_SIZE);
	}

	/**
	 * Return the last used source string
	 * @return source
	 */
	public String getLastSource() {
		return lastSources.last();
	}
	
	/**
	 * Return the last used source strings index 0 is the most recent one
	 * @return ArrayList of the source strings
	 */
	public ArrayList<String> getLastSources() {
		return lastSources;
	}
	
	/**
	 * Set the list of last used source strings
	 * @param sources
	 */
	public void setLastSources(ArrayList<String> sources) {
		lastSources = new MRUList<String>(sources);
		lastSources.ensureCapacity(MRULIST_SIZE);
	}
	
	/**
	 * @return the current object filter
	 */
	public Filter getFilter() {
		return filter;
	}

	/**
	 * Set the object filter
	 * @param filter
	 */
	public void setFilter(Filter filter) {
		this.filter = filter;
	}
	
	/**
	 * Display a warning if an operation on the element e would effect a filtered/hidden object
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param e
	 */
	private <T extends OsmElement> void displayAttachedObjectWarning(@Nullable FragmentActivity activity, T e) {
		ArrayList<T> a = new ArrayList<T>();
		a.add(e);
		displayAttachedObjectWarning(activity, a);
	}
	
	/**
	 * Display a warning if an operation on the element e1 or e2 would effect a filtered/hidden object
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param e1
	 * @param e2
	 */
	private <T extends OsmElement> void displayAttachedObjectWarning(@Nullable FragmentActivity activity, T e1, T e2) {
		ArrayList<T> a = new ArrayList<T>();
		a.add(e1);
		a.add(e2);
		displayAttachedObjectWarning(activity, a);
	}
	
	/**
	 * Display a warning if an operation on the element e1 or e2 would effect a filtered/hidden object
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param e1
	 * @param e2
	 * @param checkRelationsOnly
	 */
	private <T extends OsmElement> void displayAttachedObjectWarning(@Nullable FragmentActivity activity, T e1, T e2, boolean checkRelationsOnly) {
		ArrayList<T> a = new ArrayList<T>();
		a.add(e1);
		a.add(e2);
		displayAttachedObjectWarning(activity,a, checkRelationsOnly);
	}
	
	/**
	 * Display a warning if an operation on the elements included in list would effect a filtered/hidden object
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param list
	 */
	private <T extends OsmElement> void displayAttachedObjectWarning(@Nullable FragmentActivity activity, Collection<T> list) {
		displayAttachedObjectWarning(activity,list, false);
	}

	
	/**
	 * Display a warning if an operation on the elements included in list would effect a filtered/hidden object
	 * @param activity activity this method was called from, if null no warnings will be displayed
	 * @param list
	 * @param checkRelationsOnly
	 */
	private <T extends OsmElement> void displayAttachedObjectWarning(@Nullable FragmentActivity activity, Collection<T> list, boolean checkRelationsOnly) {
		if (getFilter() != null && showAttachedObjectWarning()) {
			elementLoop:
				for (OsmElement e:list) {
					if (!checkRelationsOnly) {
						if (e instanceof Node) {
							List<Way> ways = getWaysForNode((Node)e);
							if (ways.size() > 0) {
								for (Way w:ways) {
									if (!getFilter().include(w, false)) {
										AttachedObjectWarning.showDialog(activity);
										break elementLoop;
									}
								}
							}
						} else if (e instanceof Way) {
							for (Node n:((Way)e).getNodes()) {
								List<Way> ways = getWaysForNode(n);
								if (ways.size() > 0) {
									for (Way w:ways) {
										if (!getFilter().include(w, false)) {
											AttachedObjectWarning.showDialog(activity);
											break elementLoop;
										}
									}
								}
							}
						}
					}
					if (e.hasParentRelations()) {
						for (Relation r:e.getParentRelations()) {
							if (!getFilter().include(r, false)) {
								AttachedObjectWarning.showDialog(activity);
								break elementLoop;
							}
						}
					}
				}
		}
	}
}
