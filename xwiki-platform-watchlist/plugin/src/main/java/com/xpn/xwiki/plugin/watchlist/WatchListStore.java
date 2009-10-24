/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xpn.xwiki.plugin.watchlist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.DocumentDeleteEvent;
import org.xwiki.observation.event.DocumentSaveEvent;
import org.xwiki.observation.event.DocumentUpdateEvent;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.StaticListClass;

/**
 * WatchList store class. Handles user subscription storage.
 * 
 * @version $Id$
 */
@SuppressWarnings("serial")
public class WatchListStore implements EventListener
{
    /**
     * Character used to separated elements in Watchlist lists (pages, spaces, etc).
     */
    public static final String WATCHLIST_ELEMENT_SEP = ",";

    /**
     * Character used to separated wiki and space in XWiki model.
     */
    public static final String WIKI_SPACE_SEP = ":";
    
    /**
     * Character used to separated space and page in XWiki model.
     */
    public static final String SPACE_PAGE_SEP = ".";
    
    /**
     * Space of the scheduler application.
     */
    public static final String SCHEDULER_SPACE = "Scheduler";

    /**
     * List of elements that can be watched.
     */
    public enum ElementType
    {
        /**
         * Wiki.
         */
        WIKI,
        /**
         * Space.
         */
        SPACE,
        /**
         * Document.
         */
        DOCUMENT,
        /**
         * User.
         */
        USER
    }

    /**
     * The name of the listener.
     */
    private static final String LISTENER_NAME = "watchliststore";

    /**
     * The events to match.
     */
    private static final List<Event> LISTENER_EVENTS = new ArrayList<Event>()
    {
        {
            add(new DocumentSaveEvent());
            add(new DocumentUpdateEvent());
            add(new DocumentDeleteEvent());
        }
    };

    /**
     * Logger.
     */
    private static final Log LOG = LogFactory.getLog(WatchListStore.class);

    /**
     * XWiki Class used to store user subscriptions.
     */
    private static final String WATCHLIST_CLASS = "XWiki.WatchListClass";

    /**
     * Property of the watchlist class used to store the notification interval preference.
     */
    private static final String WATCHLIST_CLASS_INTERVAL_PROP = "interval";

    /**
     * Property of the watchlist class used to store the list of wikis to watch.
     */
    private static final String WATCHLIST_CLASS_WIKIS_PROP = "wikis";

    /**
     * Property of the watchlist class used to store the list of spaces to watch.
     */
    private static final String WATCHLIST_CLASS_SPACES_PROP = "spaces";

    /**
     * Property of the watchlist class used to store the list of documents to watch.
     */
    private static final String WATCHLIST_CLASS_DOCUMENTS_PROP = "documents";
    
    /**
     * Property of the watchlist class used to store the list of users to watch.
     */
    private static final String WATCHLIST_CLASS_USERS_PROP = "users";

    /**
     * Watchlist jobs document names in the wiki.
     */
    private List<String> jobDocumentNames;

    /**
     * List of subscribers in the wiki farm.
     */
    private Map<String, List<String>> subscribers = new HashMap<String, List<String>>();

    /**
     * Create or update the watchlist class properties.
     * 
     * @param watchListClass document in which the class must be created
     * @param context the XWiki context
     * @return true if the class properties have been created or modified
     * @throws XWikiException when retrieving of watchlist jobs in the wiki fails
     */    
    private boolean initWatchListClassProperties(XWikiDocument watchListClass, XWikiContext context)
        throws XWikiException
    {
        boolean needsUpdate = false;
        BaseClass bclass = watchListClass.getxWikiClass();
        bclass.setName(WATCHLIST_CLASS);

        needsUpdate |= bclass.addStaticListField(WATCHLIST_CLASS_INTERVAL_PROP, "Email notifications interval", "");

        // Check that the interval property contains all the available jobs
        StaticListClass intervalClass = (StaticListClass) bclass.get(WATCHLIST_CLASS_INTERVAL_PROP);
        List<String> intervalValues = intervalClass.getList(context);
        List<String> newInterval = ListUtils.intersection(jobDocumentNames, intervalValues);
        boolean intervalNeedsUpdate = false;

        // Look for missing jobs, build a complete list
        for (String jobName : (List<String>) ListUtils.subtract(jobDocumentNames, intervalValues)) {
            newInterval.add(jobName);
            intervalNeedsUpdate = true;
        }

        // Look for outdated jobs
        if (ListUtils.subtract(intervalValues, jobDocumentNames).size() > 0) {
            intervalNeedsUpdate = true;
        }

        // Save the complete list in the interval prop
        if (intervalNeedsUpdate) {
            intervalClass.setValues(StringUtils.join(newInterval, "|"));
            needsUpdate = true;
        }

        // Create storage properties
        needsUpdate |= bclass.addTextAreaField(WATCHLIST_CLASS_WIKIS_PROP, "Wiki list", 80, 5);
        needsUpdate |= bclass.addTextAreaField(WATCHLIST_CLASS_SPACES_PROP, "Space list", 80, 5);
        needsUpdate |= bclass.addTextAreaField(WATCHLIST_CLASS_DOCUMENTS_PROP, "Document list", 80, 5);
        needsUpdate |= bclass.addTextAreaField(WATCHLIST_CLASS_USERS_PROP, "User list", 80, 5);

        return needsUpdate;
    }

    /**
     * Creates the WatchList xwiki class.
     * 
     * @param context Context of the request
     * @throws XWikiException if class fields cannot be created
     */
    private void initWatchListClass(XWikiContext context) throws XWikiException
    {
        XWikiDocument doc;
        boolean needsUpdate = false;

        try {
            doc = context.getWiki().getDocument(WATCHLIST_CLASS, context);
        } catch (Exception e) {
            doc = new XWikiDocument();
            String[] spaceAndName = WATCHLIST_CLASS.split(SPACE_PAGE_SEP);
            doc.setSpace(spaceAndName[0]);
            doc.setName(spaceAndName[1]);
            needsUpdate = true;
        }

        needsUpdate = initWatchListClassProperties(doc, context);

        if (StringUtils.isBlank(doc.getCreator())) {
            needsUpdate = true;
            doc.setCreator(WatchListPlugin.DEFAULT_DOC_AUTHOR);
        }
        if (StringUtils.isBlank(doc.getAuthor())) {
            needsUpdate = true;
            doc.setAuthor(doc.getCreator());
        }
        if (StringUtils.isBlank(doc.getParent())) {
            needsUpdate = true;
            doc.setParent("XWiki.XWikiClasses");
        }
        if (StringUtils.isBlank(doc.getTitle())) {
            needsUpdate = true;
            doc.setTitle("XWiki WatchList Notification Rules Class");
        }
        if (StringUtils.isBlank(doc.getContent()) || !XWikiDocument.XWIKI20_SYNTAXID.equals(doc.getSyntaxId())) {
            needsUpdate = true;      
            doc.setContent("{{include document=\"XWiki.ClassSheet\" /}}");
            doc.setSyntaxId(XWikiDocument.XWIKI20_SYNTAXID);
        }

        if (needsUpdate) {
            context.getWiki().saveDocument(doc, "", true, context);
        }
    }

    /**
     * Retrieves all the users with a WatchList object in their profile.
     * 
     * @param jobName name of the job to init the cache for
     * @param context the XWiki context
     */
    private void initSubscribersCache(String jobName, XWikiContext context)
    {
        // init subscribers cache
        List<Object> queryParams = new ArrayList<Object>();
        queryParams.add(WATCHLIST_CLASS);
        queryParams.add(jobName);

        List<String> subscribersForJob =
            globalSearchDocuments(
                ", BaseObject as obj, StringProperty as prop where doc.fullName=obj.name and obj.className=?"
                    + " and obj.id=prop.id.id and prop.value=?", 0, 0, queryParams, context);
        subscribers.put(jobName, subscribersForJob);
    }
    
    /**
     * Destroy subscribers cache for the given job.
     * 
     * @param jobName name of the job for which the cache must be destroyed
     * @param context the XWiki context
     */
    private void destroySubscribersCache(String jobName, XWikiContext context)
    {
        // init subscribers cache        
        subscribers.remove(jobName);
    }

    /**
     * Init watchlist store. Get all the jobs present in the wiki. Create the list of subscribers.
     * 
     * @param context the XWiki context
     * @throws XWikiException if the watchlist XWiki class creation fails
     */
    public void init(XWikiContext context) throws XWikiException
    {
        // Retreive jobs in the wiki, must be done first since initWatchListClass relies on them
        jobDocumentNames =
            context.getWiki().getStore().searchDocumentsNames(
                ", BaseObject as obj where doc.fullName=obj.name and obj.className='"
                    + WatchListJobManager.WATCHLIST_JOB_CLASS + "'", context);

        initWatchListClass(context);
        
        for (String jobDocumentName : jobDocumentNames) {
            initSubscribersCache(jobDocumentName, context);
        }
    }

    /**
     * Virtual init for watchlist store. Create the WatchList XWiki class.
     * 
     * @param context the XWiki context
     * @throws XWikiException if the watchlist XWiki class creation fails
     */
    public void virtualInit(XWikiContext context) throws XWikiException
    {
        // Create the watchlist class if needed
        initWatchListClass(context);
    }

    /**
     * @param jobId ID of the job.
     * @return subscribers for the given notification job.
     */
    public List<String> getSubscribersForJob(String jobId)
    {
        List<String> result = subscribers.get(jobId);

        if (result == null) {
            return new ArrayList<String>();
        } else {
            return result;
        }
    }

    /**
     * Register a new subscriber for the given job. 
     * 
     * @param jobId ID of the job
     * @param user subscriber to add
     */
    private void addSubscriberForJob(String jobId, String user)
    {
        List<String> subForJob = subscribers.get(jobId);

        if (subForJob != null && !subForJob.contains(user)) {
            subForJob.add(user);
        }
    }

    /**
     * Remove a subscriber for the given job.
     * 
     * @param jobId ID of the job
     * @param user subscriber to remove
     */
    private void removeSubscriberForJob(String jobId, String user)
    {
        List<String> subForJob = subscribers.get(jobId);

        if (subForJob != null && subForJob.contains(user)) {
            subForJob.remove(user);
        }
    }    

    /**
     * Get watched elements for the given element type and user.
     * 
     * @param user user to match
     * @param type element type to match
     * @param context the XWiki context
     * @return matching elements
     * @throws XWikiException if retrieval of elements fails
     */
    public List<String> getWatchedElements(String user, ElementType type, XWikiContext context) throws XWikiException
    {
        BaseObject watchListObject = this.getWatchListObject(user, context);
        String watchedItems = watchListObject.getLargeStringValue(getWatchListClassPropertyForType(type)).trim();
        List<String> elements = new ArrayList<String>();
        elements.addAll(Arrays.asList(watchedItems.split(WATCHLIST_ELEMENT_SEP)));
        return elements;
    }

    /**
     * Is the element watched by the given user.
     * 
     * @param element the element to look for
     * @param user user to check
     * @param type type of the element
     * @param context the XWiki context
     * @return true if the element is watched by the user, false otherwise
     * @throws XWikiException if the retrieval of watched elements fails
     */
    public boolean isWatched(String element, String user, ElementType type, XWikiContext context) throws XWikiException
    {
        return getWatchedElements(user, type, context).contains(element);
    }

    /**
     * Get the name of the XClass property the given type is stored in.
     * 
     * @param type type to retrieve
     * @return the name of the XClass property
     */
    private String getWatchListClassPropertyForType(ElementType type)
    {
        if (ElementType.WIKI.equals(type)) {
            return WATCHLIST_CLASS_WIKIS_PROP;
        } else if (ElementType.SPACE.equals(type)) {
            return WATCHLIST_CLASS_SPACES_PROP;
        } else if (ElementType.DOCUMENT.equals(type)) {
            return WATCHLIST_CLASS_DOCUMENTS_PROP;
        } else if (ElementType.USER.equals(type)) {
            return WATCHLIST_CLASS_USERS_PROP;
        } else {
            return StringUtils.EMPTY;
        }
    }

    /**
     * Add the specified element (document or space) to the corresponding list in the user's WatchList.
     * 
     * @param user XWikiUser
     * @param newWatchedElement The name of the element to add (document of space)
     * @param type type of the element to remove
     * @param context Context of the request
     * @return True if the element was'nt already in list
     * @throws XWikiException if the modification hasn't been saved
     */
    public boolean addWatchedElement(String user, String newWatchedElement, ElementType type, XWikiContext context)
        throws XWikiException
    {
        String elementToWatch = newWatchedElement;
        
        if (!ElementType.WIKI.equals(type) && !newWatchedElement.contains(WIKI_SPACE_SEP)) {
            elementToWatch = context.getDatabase() + WIKI_SPACE_SEP + newWatchedElement;
        }

        if (this.isWatched(elementToWatch, user, type, context)) {
            return false;
        }

        List<String> watchedElements = getWatchedElements(user, type, context);
        watchedElements.add(elementToWatch);

        this.setWatchListElementsProperty(user, type, watchedElements, context);
        return true;
    }

    /**
     * Remove the specified element (document or space) from the corresponding list in the user's WatchList.
     * 
     * @param user XWiki User
     * @param watchedElement The name of the element to remove (document or space)
     * @param type type of the element to remove
     * @param context Context of the request
     * @return True if the element was in list and has been removed, false if the element was'nt in the list
     * @throws XWikiException If the WatchList Object cannot be retreived or if the user's profile cannot be saved
     */
    public boolean removeWatchedElement(String user, String watchedElement, ElementType type, XWikiContext context)
        throws XWikiException
    {
        String elementToRemove = watchedElement;
        
        if (!ElementType.WIKI.equals(type) && !watchedElement.contains(WIKI_SPACE_SEP)) {
            elementToRemove = context.getDatabase() + WIKI_SPACE_SEP + watchedElement;
        }

        if (!this.isWatched(elementToRemove, user, type, context)) {
            return false;
        }
        
        List<String> watchedElements = getWatchedElements(user, type, context);
        watchedElements.remove(elementToRemove);

        this.setWatchListElementsProperty(user, type, watchedElements, context);
        return true;
    }

    /**
     * Creates a WatchList XWiki Object in the user's profile's page.
     * 
     * @param user XWiki User
     * @param context Context of the request
     * @return the watchlist object that has been created
     * @throws XWikiException if the document cannot be saved
     */
    public BaseObject createWatchListObject(String user, XWikiContext context) throws XWikiException
    {
        XWikiDocument userDocument = context.getWiki().getDocument(user, context);
        int nb = userDocument.createNewObject(WATCHLIST_CLASS, context);
        BaseObject wObj = userDocument.getObject(WATCHLIST_CLASS, nb);
        context.getWiki().saveDocument(userDocument, context.getMessageTool().get("watchlist.create.object"), true,
            context);
        return wObj;
    }

    /**
     * Gets the WatchList XWiki Object from user's profile's page.
     * 
     * @param user XWiki User
     * @param context Context of the request
     * @return the WatchList XWiki BaseObject
     * @throws XWikiException if BaseObject creation fails
     */
    public BaseObject getWatchListObject(String user, XWikiContext context) throws XWikiException
    {
        XWikiDocument userDocument = context.getWiki().getDocument(user, context);
        BaseObject obj = userDocument.getObject(WATCHLIST_CLASS);
        if (obj == null) {
            obj = this.createWatchListObject(user, context);
        }
        return obj;
    }

    /**
     * Sets a largeString property in the user's WatchList Object, then saves the user's profile.
     * 
     * @param user XWiki User
     * @param type Elements type
     * @param elements List of elements to store
     * @param context Context of the request
     * @throws XWikiException if the user's profile cannot be saved
     */
    private void setWatchListElementsProperty(String user, ElementType type, List<String> elements, 
        XWikiContext context) throws XWikiException
    {
        XWikiDocument userDocument = context.getWiki().getDocument(user, context);
        userDocument.setLargeStringValue(WATCHLIST_CLASS, getWatchListClassPropertyForType(type), StringUtils.join(
            elements, WATCHLIST_ELEMENT_SEP));
        userDocument.isMinorEdit();
        context.getWiki().saveDocument(userDocument, context.getMessageTool().get("watchlist.save.object"), true,
            context);
    }

    /**
     * Search documents on all the wikis by passing HQL where clause values as parameters.
     * 
     * @param request The HQL where clause.
     * @param nb Number of results to retrieve
     * @param start Offset to use in the search query
     * @param values The where clause values that replaces the question marks (?)
     * @param context The XWiki context
     * @return a list of document names prefixed with the wiki they come from ex : xwiki:Main.WebHome
     */
    public List<String> globalSearchDocuments(String request, int nb, int start, List<Object> values,
        XWikiContext context)
    {
        List<String> wikiServers = new ArrayList<String>();
        List<String> results = new ArrayList<String>();

        if (context.getWiki().isVirtualMode()) {
            try {
                wikiServers = context.getWiki().getVirtualWikisDatabaseNames(context);
                if (!wikiServers.contains(context.getMainXWiki())) {
                    wikiServers.add(context.getMainXWiki());
                }
            } catch (Exception e) {
                LOG.error("error getting list of wiki servers", e);
            }
        } else {
            wikiServers = new ArrayList<String>();
            wikiServers.add(context.getMainXWiki());
        }
        
        String oriDatabase = context.getDatabase();

        try {
            for (String wiki : wikiServers) {                
                String wikiPrefix = wiki + WIKI_SPACE_SEP;
                context.setDatabase(wiki);
                try {
                    List<String> upDocsInWiki =
                        context.getWiki().getStore().searchDocumentsNames(request, 0, 0, values, context);
                    Iterator<String> it = upDocsInWiki.iterator();
                    while (it.hasNext()) {
                        results.add(wikiPrefix + it.next());
                    }
                } catch (Exception e) {
                    LOG.error("error getting list of documents in the wiki : " + wiki, e);
                }
            }
        } finally {
            context.setDatabase(oriDatabase);
        }
        
        return results;
    }

    /**
     * Manage events affecting watchlist job objects.
     * 
     * @param originalDoc document version before the event occurred
     * @param currentDoc document version after event occurred
     * @param context the XWiki context
     */
    private void watchListJobObjectsEventHandler(XWikiDocument originalDoc, XWikiDocument currentDoc, 
        XWikiContext context)
    {
        boolean reinitWatchListClass = false;

        BaseObject originalJob = originalDoc.getObject(WatchListJobManager.WATCHLIST_JOB_CLASS);
        BaseObject currentJob = currentDoc.getObject(WatchListJobManager.WATCHLIST_JOB_CLASS);

        if (originalJob != null && currentJob == null) {
            if (jobDocumentNames.contains(originalDoc.getFullName())) {
                int index = jobDocumentNames.indexOf(originalDoc.getFullName());
                jobDocumentNames.remove(index);
                destroySubscribersCache(originalDoc.getFullName(), context);
                reinitWatchListClass = true;
            }
        }

        if (originalJob == null && currentJob != null) {
            jobDocumentNames.add(currentDoc.getFullName());
            initSubscribersCache(currentDoc.getFullName(), context);
            reinitWatchListClass = true;
        }

        if (reinitWatchListClass) {
            try {
                initWatchListClass(context);
            } catch (XWikiException e) {
                // Do nothing
            }
        }
    }
    
    /**
     * Manage events affecting watchlist objects.
     * 
     * @param originalDoc document version before the event occurred
     * @param currentDoc document version after event occurred
     * @param context the XWiki context
     */
    private void watchListObjectsEventHandler(XWikiDocument originalDoc, XWikiDocument currentDoc, 
        XWikiContext context) 
    {
        String wiki = context.getDatabase();
        BaseObject originalWatchListObj = originalDoc.getObject(WATCHLIST_CLASS);
        BaseObject currentWatchListObj = currentDoc.getObject(WATCHLIST_CLASS);        
        
        if (originalWatchListObj != null) {
            // Existing subscriber

            String oriInterval = originalWatchListObj.getStringValue(WATCHLIST_CLASS_INTERVAL_PROP);

            // If a subscriber has been deleted, remove it from our cache and exit
            if (currentWatchListObj == null) {
                removeSubscriberForJob(oriInterval, wiki + WIKI_SPACE_SEP + originalDoc.getFullName());
                return;
            }

            // If the subscription object has been deleted, remove the subscriber from our cache and exit
            if (originalWatchListObj != null && currentDoc.getObject(WATCHLIST_CLASS) == null) {
                removeSubscriberForJob(oriInterval, wiki + WIKI_SPACE_SEP + originalDoc.getFullName());
                return;
            }

            // Modification of the interval
            String newInterval = currentWatchListObj.getStringValue(WATCHLIST_CLASS_INTERVAL_PROP);
            
            if (!newInterval.equals(oriInterval)) {
                removeSubscriberForJob(oriInterval, wiki + WIKI_SPACE_SEP + originalDoc.getFullName());
                addSubscriberForJob(newInterval, wiki + WIKI_SPACE_SEP + currentDoc.getFullName());
            }
        }

        if ((originalWatchListObj == null || originalDoc == null) && currentWatchListObj != null) {
            // New subscriber
            String newInterval = currentWatchListObj.getStringValue(WATCHLIST_CLASS_INTERVAL_PROP);
            
            addSubscriberForJob(newInterval, wiki + WIKI_SPACE_SEP + currentDoc.getFullName());
        }
    }
    

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#onEvent(org.xwiki.observation.event.Event, java.lang.Object,
     *      java.lang.Object)
     */
    public void onEvent(Event event, Object source, Object data)
    {
        XWikiDocument currentDoc = (XWikiDocument) source;
        XWikiDocument originalDoc = currentDoc.getOriginalDocument();
        XWikiContext context = (XWikiContext) data;
        
        watchListJobObjectsEventHandler(originalDoc, currentDoc, context);
        watchListObjectsEventHandler(originalDoc, currentDoc, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#getEvents()
     */
    public List<Event> getEvents()
    {
        return LISTENER_EVENTS;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.observation.EventListener#getName()
     */
    public String getName()
    {
        return LISTENER_NAME;
    }
}
