/*******************************************************************************
 * Copyright 2012, The Infinit.e Open Source Project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package com.ikanow.infinit.e.api.config.source;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.ArrayUtils;
import org.apache.log4j.Logger;
import org.bson.types.ObjectId;

import com.ikanow.infinit.e.api.custom.mapreduce.CustomHandler;
import com.ikanow.infinit.e.api.knowledge.federated.SimpleFederatedQueryEngine;
import com.ikanow.infinit.e.api.utils.SocialUtils;
import com.ikanow.infinit.e.api.utils.PropertiesManager;
import com.ikanow.infinit.e.api.utils.RESTTools;
import com.ikanow.infinit.e.data_model.utils.SendMail;
import com.ikanow.infinit.e.data_model.Globals;
import com.ikanow.infinit.e.data_model.InfiniteEnums;
import com.ikanow.infinit.e.data_model.InfiniteEnums.HarvestEnum;
import com.ikanow.infinit.e.data_model.api.ApiManager;
import com.ikanow.infinit.e.data_model.api.BasePojoApiMap;
import com.ikanow.infinit.e.data_model.api.ResponsePojo;
import com.ikanow.infinit.e.data_model.api.ResponsePojo.ResponseObject;
import com.ikanow.infinit.e.data_model.api.config.SourcePojoApiMap;
import com.ikanow.infinit.e.data_model.api.config.SourcePojoSubstitutionApiMap;
import com.ikanow.infinit.e.data_model.api.knowledge.AdvancedQueryPojo;
import com.ikanow.infinit.e.data_model.api.knowledge.DocumentPojoApiMap;
import com.ikanow.infinit.e.data_model.api.knowledge.StatisticsPojo;
import com.ikanow.infinit.e.data_model.index.ElasticSearchManager;
import com.ikanow.infinit.e.data_model.store.DbManager;
import com.ikanow.infinit.e.data_model.store.MongoDbManager;
import com.ikanow.infinit.e.data_model.store.config.source.SourceFederatedQueryConfigPojo;
import com.ikanow.infinit.e.data_model.store.config.source.SourceHarvestStatusPojo;
import com.ikanow.infinit.e.data_model.store.config.source.SourcePipelinePojo;
import com.ikanow.infinit.e.data_model.store.config.source.SourcePojo;
import com.ikanow.infinit.e.data_model.store.document.DocumentPojo;
import com.ikanow.infinit.e.data_model.store.social.community.CommunityApprovePojo;
import com.ikanow.infinit.e.data_model.store.social.community.CommunityMemberPojo;
import com.ikanow.infinit.e.data_model.store.social.community.CommunityPojo;
import com.ikanow.infinit.e.data_model.store.social.person.PersonPojo;
import com.ikanow.infinit.e.harvest.HarvestController;
import com.ikanow.infinit.e.harvest.extraction.document.distributed.DistributedHarvester;
import com.ikanow.infinit.e.processing.generic.GenericProcessingController;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;

/**
 * This class is for all operations related to the retrieval, addition
 * or update of sources within the system
 * @author cmorgan
 */
public class SourceHandler
{
	private static final Logger logger = Logger.getLogger(SourceHandler.class);
	
	// These 2 fns are set by isOwnerModeratorOrSysAdmin
	private boolean isOwnerOrModerator = false;
	private boolean isSysAdmin = false;
	
	///////////////////////////////////////////////////////////////////////////////////////////////
	
	// ACTIONS ON INDIVIDUAL SOURCES
		
	/**
	 * getInfo
	 * Retrieve a source
	 * @param idStr
	 * @param userid
	 * @return
	 */
	public ResponsePojo getInfo(String idStr, String userIdStr) 
	{
		ResponsePojo rp = new ResponsePojo();
		
		try 
		{	
			// Return source specified by _id
			BasicDBObject query = new BasicDBObject();
			try {
				query.put(SourcePojo._id_, new ObjectId(idStr));				
			}
			catch (Exception e){ // Obvious feature, allow key to be specified as well as _id
				query.put(SourcePojo.key_, idStr);
			}
		
			// Only return those community IDs that the user is a member of unless the 
			// user is an administrator in which case you can return all community IDs
			Set<ObjectId> communityIdSet = new TreeSet<ObjectId>();
			Set<ObjectId> ownedOrModeratedCommunityIdSet = new TreeSet<ObjectId>();
			boolean bAdmin = false;
			if (RESTTools.adminLookup(userIdStr))
			{
				bAdmin = true;
			}
			else
			{
				communityIdSet = SocialUtils.getUserCommunities(userIdStr);
				query.put(SourcePojo.communityIds_, new BasicDBObject(MongoDbManager.in_, communityIdSet)); // (security)
			}
			SourcePojo source = SourcePojo.fromDb(DbManager.getIngest().getSource().findOne(query), SourcePojo.class);
			if (null == source) {
				rp.setResponse(new ResponseObject("Source Info",false,"error retrieving source info (or permissions error)"));				
			}
			else {
				ObjectId userId = null;
				if (bAdmin) {
					communityIdSet = source.getCommunityIds();
				}
				else if (!source.isPublic()) { // (otherwise can bypass this)
					userId = new ObjectId(userIdStr);
					if (userId.equals(source.getOwnerId())) {
						userId = null; // (no need to mess about with sets)
					}
					else {
						for (ObjectId communityId: communityIdSet) {
							if (isOwnerOrModerator(communityId.toString(), userIdStr)) {
								ownedOrModeratedCommunityIdSet.add(communityId);													
							}
						}
					}
				}
				//TESTED (source owner, in community) 
				
				rp.setData(source, new SourcePojoApiMap(userId, communityIdSet, ownedOrModeratedCommunityIdSet));
				rp.setResponse(new ResponseObject("Source Info",true,"Successfully retrieved source info"));
			}
		} 
		catch (Exception e)
		{
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Source Info",false,"error retrieving source info"));
		}
		return rp;
	}
	
	/**
	 * addSource
	 * Add a new source
	 * @param sourcetitle
	 * @param sourcedesc
	 * @param sourceurl
	 * @param extracttype
	 * @param sourcetags
	 * @param mediatype
	 * @param communityIdStr
	 * @param userIdStr
	 * @return
	 */
	public ResponsePojo addSource(String sourcetitle, String sourcedesc, String sourceurl, String extracttype,
			String sourcetags, String mediatype, String communityIdStr, String userIdStr) 
	{
		ResponsePojo rp = new ResponsePojo();
		
		if (userIdStr.equals(communityIdStr)) { // Don't allow personal sources
			rp.setResponse(new ResponseObject("Source", false, "No longer allowed to create sources in personal communities"));
			return rp;			
		}
		//(TESTED - cut and paste from saveSource)
		
		try 
		{
			communityIdStr = allowCommunityRegex(userIdStr, communityIdStr);
			boolean isApproved = isOwnerModeratorOrContentPublisherOrSysAdmin(communityIdStr, userIdStr);
			if (!SocialUtils.isDataAllowed(communityIdStr)) {
				rp.setResponse(new ResponseObject("Source", false, "Not allowed to create sources in user groups"));
				return rp;							
			}//(TESTED - cut and paste from saveSource)

			//create source object
			SourcePojo newSource = new SourcePojo();
			newSource.setId(new ObjectId());
			newSource.setTitle(sourcetitle);
			newSource.setDescription(sourcedesc);
			newSource.setUrl(sourceurl); // (key derived below)
			newSource.setExtractType(extracttype);
			newSource.setOwnerId(new ObjectId(userIdStr));
			newSource.setTags(new HashSet<String>(Arrays.asList(sourcetags.split(","))));
			newSource.setMediaType(mediatype);
			newSource.addToCommunityIds(new ObjectId(communityIdStr));
			newSource.setApproved(isApproved);
			newSource.setCreated(new Date());
			newSource.setModified(new Date());
			newSource.generateShah256Hash();
			
			newSource.setKey(validateSourceKey(newSource.getId(), newSource.generateSourceKey()));
			
			///////////////////////////////////////////////////////////////////////
			// Add the new source to the harvester.sources collection
			try
			{
				// Need to double check that the community has an index (for legacy reasons):
				if (null == DbManager.getIngest().getSource().findOne(new BasicDBObject(SourcePojo.communityIds_, 
						new BasicDBObject(MongoDbManager.in_, newSource.getCommunityIds()))))
				{
					for (ObjectId id: newSource.getCommunityIds()) {
						GenericProcessingController.recreateCommunityDocIndex_unknownFields(id, false);
					}
				}
				//TESTED (cut and paste from saveSource)
				
				DbManager.getIngest().getSource().save(newSource.toDb());
				String awaitingApproval = (isApproved) ? "" : " Awaiting approval by the community owner or moderators.";
				
				if (isUniqueSource(newSource, Arrays.asList(new ObjectId(communityIdStr))))
				{				
					rp.setResponse(new ResponseObject("Source", true, "New source added successfully." + 
							awaitingApproval));
				}
				else {
					rp.setResponse(new ResponseObject("Source", true, "New source added successfully. Note functionally identical sources are also present within your communities, which may waste system resources." + 
							awaitingApproval));					
				}
				
				///////////////////////////////////////////////////////////////////////
				// If the user is not the owner or moderator we need to send the owner
				// and email asking them to approve or reject the source
				if (!isOwnerOrModerator && !isSysAdmin)
				{
					emailSourceApprovalRequest(newSource);
				}
			}
			catch (Exception e)
			{
				rp.setResponse(new ResponseObject("Source", false, "Unable to add new source. Error: " + e.getMessage()));
			}
		}
		catch (Exception e)
		{
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Add Source", false, "Error adding source"));
		}
		return rp;
	}

	
	
	/**
	 * saveSource
	 * Adds a new, or updates an existing, source where sourceString
	 * is a JSON representation or a SourcePojo
	 * @param sourceString
	 * @param userIdStr
	 * @param communityIdStr
	 * @return
	 */
	public ResponsePojo saveSource(String sourceString, String userIdStr, String communityIdStr)
	{
		ResponsePojo rp = new ResponsePojo();
		
		try {
			communityIdStr = allowCommunityRegex(userIdStr, communityIdStr);
			if (!SocialUtils.isDataAllowed(communityIdStr)) {
				rp.setResponse(new ResponseObject("Source", false, "Not allowed to create sources in user groups"));
				return rp;							
			}//TODO (INF-2866): TOTEST
			
			boolean isApproved = isOwnerModeratorOrContentPublisherOrSysAdmin(communityIdStr, userIdStr);
			
			///////////////////////////////////////////////////////////////////////
			// Try parsing the json into a SourcePojo object
			SourcePojo source = null;
			Set<ObjectId> communityIdSet = new TreeSet<ObjectId>();
			try
			{
				///////////////////////////////////////////////////////////////////////
				// Note: Remove any communityids already in the source and set groupdID to
				// the communityid param (supports multiple IDs in a comma separated list)
				communityIdSet.add(new ObjectId(communityIdStr));
				
				source = ApiManager.mapFromApi(sourceString, SourcePojo.class, new SourcePojoApiMap(communityIdSet));
				if (null == source.getCommunityIds()) {
					source.setCommunityIds(new HashSet<ObjectId>());
				}
				for (ObjectId sid: communityIdSet) {
					source.getCommunityIds().add(sid);
				}
				source.setFederatedQueryCommunityIds(null); // (can be filled in by fillInSourcePipelineFields() below)
				source.fillInSourcePipelineFields(); // (needs to be after the community ids)
				
				int extractType = InfiniteEnums.castExtractType(source.getExtractType()); // (created by fillInSourcePipelineFields)
				if ((InfiniteEnums.CUSTOM != extractType) && (InfiniteEnums.POSTPROC != extractType) && (InfiniteEnums.FEDERATED != extractType)) {
					//TODO (INF-2685): at some point when are allowed to create docs from custom sources will need to tweak this
					if (userIdStr.equals(communityIdStr)) { // Don't allow personal sources
						rp.setResponse(new ResponseObject("Source", false, "No longer allowed to create sources in personal communities - EXCEPT for custom/post processing/federated"));
						return rp;			
					}
				}
				//TESTED								
				
				// RSS search harvest types tend to be computationally expensive and therefore
				// should be done less frequently (by default once/4-hours seems good):
				if (sourceSearchesWeb(source)) {
					// If the search cycle has not been specified, use a default:
					if (null == source.getSearchCycle_secs()) {
						source.setSearchCycle_secs(4*3600); // (ie 4 hours)
					}
				}//TESTED
				else if (InfiniteEnums.POSTPROC == InfiniteEnums.castExtractType(source.getExtractType())) {
					if (null == source.getSearchCycle_secs()) {
						source.setSearchCycle_secs(0); // (ie run once then suspend)
					}
					else if ((0 != source.getSearchCycle_secs()) && (Math.abs(source.getSearchCycle_secs()) < 3600)) { // min freq is hourly
						source.setSearchCycle_secs(source.getSearchCycle_secs() < 0 ? -3600 : 3600);
					}
				}//TESTED (by hand)		
			}
			catch (Exception e)
			{
				rp.setResponse(new ResponseObject("Source", false, "Unable to serialize Source JSON. Error: " + e.getMessage()));
				return rp;
			}
			
			BasicDBObject query = null;
	
			//SPECIAL CASE: IF AN ACTIVE LOGSTASH HARVEST THEN CHECK BEFORE WE'LL PUBLISH:
			if (source.getExtractType().equalsIgnoreCase("logstash") && 
					((null == source.getSearchCycle_secs()) || (source.getSearchCycle_secs() > 0)))
			{
				ResponsePojo rpTest = this.testSource(sourceString, 0, false, false, userIdStr);
				if (!rpTest.getResponse().isSuccess()) { 
					rp.setResponse(new ResponseObject("Source", false, "Logstash not publishable. Error: " + rpTest.getResponse().getMessage()));
					return rp;
				}
				
			}//TESTED

			// (Just make sure tags are always set)
			if (null == source.getTags()) {
				source.setTags(new HashSet<String>());
			}
			
			///////////////////////////////////////////////////////////////////////
			// If source._id == null this should be a new source
			if ((source.getId() == null) && (source.getKey() == null))
			{
				///////////////////////////////////////////////////////////////////////
				// Note: Overwrite the following fields regardless of what was sent in
				source.setId(new ObjectId());
				source.setOwnerId(new ObjectId(userIdStr));
				source.setApproved(isApproved);
				source.setCreated(new Date());
				source.setModified(new Date());
				source.setUrl(source.getUrl()); 
					// (key generated below from representative URL - don't worry about the fact this field is sometimes not present)
	
				source.setKey(validateSourceKey(source.getId(), source.generateSourceKey()));
	
				source.generateShah256Hash();
					// Note: Create/update the source's Shah-256 hash 
			
				///////////////////////////////////////////////////////////////////////
				// Note: Check the SourcePojo to make sure the required fields are there
				// return an error message to the user if any are missing
				String missingFields = hasRequiredSourceFields(source);
				if (missingFields != null && missingFields.length() > 0)
				{
					rp.setResponse(new ResponseObject("Source", false, missingFields));
					return rp;
				}
				
				///////////////////////////////////////////////////////////////////////
				// Add the new source to the harvester.sources collection
				try
				{
					// Need to double check that the community has an index (for legacy reasons):
					if (null == DbManager.getIngest().getSource().findOne(new BasicDBObject(SourcePojo.communityIds_, 
							new BasicDBObject(MongoDbManager.in_, source.getCommunityIds()))))
					{
						for (ObjectId id: source.getCommunityIds()) {
							GenericProcessingController.recreateCommunityDocIndex_unknownFields(id, false);
						}
					}
					//TESTED

					if (isApproved) { // (this activity can result in custom activity, so don't do it unless the source is approved)
						try {
							new DistributedHarvester().decomposeCustomSourceIntoMultipleJobs(source, false);
						}
						catch (Throwable t) {							
							rp.setResponse(new ResponseObject("Source", false, Globals.populateStackTrace(new StringBuffer("Error creating custom source: "), t).toString()));
							return rp;
						}//TESTED (by hand)					
					}					
					DbManager.getIngest().getSource().save(source.toDb());
					if (isUniqueSource(source, communityIdSet))
					{
						rp.setResponse(new ResponseObject("Source", true, "New source added successfully."));
					}
					else { // Still allow people to add identical sources, but warn them so they can delete it if they way
						rp.setResponse(new ResponseObject("Source", true, "New source added successfully. Note functionally identical sources are also present within your communities, which may waste system resources."));					
					}
					rp.setData(source, new SourcePojoApiMap(null, communityIdSet, null));
				}
				catch (Exception e)
				{
					rp.setResponse(new ResponseObject("Source", false, "Unable to add new source. Error: " + e.getMessage()));
				}
				
				///////////////////////////////////////////////////////////////////////
				// If the user is not the owner or moderator we need to send the owner
				// and email asking them to approve or reject the source
				try {
					if (!isApproved)
					{
						emailSourceApprovalRequest(source);
					}
				}
				catch (Exception e) { // Unable to ask for permission, remove sources and error out
					logger.error("Exception Message: " + e.getMessage(), e);
					DbManager.getIngest().getSource().remove(new BasicDBObject(SourcePojo._id_, source.getId()));
					rp.setData((String)null, (BasePojoApiMap<String>)null); // (unset)
					rp.setResponse(new ResponseObject("Source", false, "Unable to email authority for permission, maybe email infrastructure isn't added? Error: " + e.getMessage()));
				}
				
			}//TESTED (behavior when an identical source is added)
	
			///////////////////////////////////////////////////////////////////////
			// Existing source, update if possible
			else
			{
				if ((null != source.getPartiallyPublished()) && source.getPartiallyPublished()) {
					rp.setResponse(new ResponseObject("Source", false, "Unable to update source - the source is currently in 'partially published' mode, because it is private and you originally accessed it without sufficient credentials. Make a note of your changes, revert, and try again."));
					return rp;					
				}//TESTED
				
				///////////////////////////////////////////////////////////////////////
				// Attempt to retrieve existing share from harvester.sources collection
				query = new BasicDBObject();
				if (null != source.getId()) {
					query.put(SourcePojo._id_, source.getId());
				}
				else if (null != source.getKey()) {
					query.put(SourcePojo.key_, source.getKey());					
				}
				try 
				{
					BasicDBObject dbo = (BasicDBObject)DbManager.getIngest().getSource().findOne(query);
					// Source doesn't exist so it can't be updated
					if (dbo == null)
					{
						rp.setResponse(new ResponseObject("Source", false, "Unable to update source. The source ID is invalid."));
						return rp;
					}
					
					SourcePojo oldSource = SourcePojo.fromDb(dbo,SourcePojo.class);
					///////////////////////////////////////////////////////////////////////
					// Note: Only an Infinit.e administrator, source owner, community owner
					// or moderator can update/edit a source
					if (null == oldSource.getOwnerId()) { // (internal error, just correct)
						oldSource.setOwnerId(new ObjectId(userIdStr));
					}
					boolean isSourceOwner = oldSource.getOwnerId().toString().equalsIgnoreCase(userIdStr);
					
					if (!isSourceOwner) {
						boolean ownerModOrApprovedSysAdmin = isApproved &&
										(SocialUtils.isOwnerOrModerator(communityIdStr, userIdStr) || RESTTools.adminLookup(userIdStr));
						
						if (!ownerModOrApprovedSysAdmin)
						{
							rp.setResponse(new ResponseObject("Source", false, "User does not have permissions to edit this source"));
							return rp;
						}
					}//TESTED - works if owner or moderator, or admin (enabled), not if not admin-enabled

					// For now, don't allow you to change communities
					int extractType = InfiniteEnums.castExtractType(source.getExtractType()); // (created by fillInSourcePipelineFields)
					if ((InfiniteEnums.CUSTOM != extractType) && (InfiniteEnums.POSTPROC != extractType) && (InfiniteEnums.FEDERATED != extractType)) {
						//TODO (INF-2685): at some point when are allowed to create docs from custom sources will need to tweak this
						if ((null == source.getCommunityIds()) || (null == oldSource.getCommunityIds()) // (robustness) 
								|| 
								!source.getCommunityIds().equals(oldSource.getCommunityIds()))
						{
							rp.setResponse(new ResponseObject("Source", false, "It is not currently possible to change the community of a published source. You must duplicate/scrub the source and re-publish it as a new source (and potentially suspend/delete this one) - EXCEPT for custom/post processing/federated"));
							return rp;
						}//TOTEST
					}
					
					// Another special case: don't allow sources to change extract type...
					if (!oldSource.getExtractType().equals(source.getExtractType())) {
						rp.setResponse(new ResponseObject("Source", false, "You cannot change extract types in sources, was: " + oldSource.getExtractType()));
						return rp;						
					}//TESTED: working and not working
					
					//isOwnerOrModerator
					
					String oldHash = source.getShah256Hash();
					
					///////////////////////////////////////////////////////////////////////
					// Note: The following fields in an existing source cannot be changed: Key
					// Make sure new source url and key match existing source values
					// (we allow URL to be changed, though obv the key won't be changed to reflect that)
					source.setKey(oldSource.getKey());
					// Overwrite/set other values in the new source from old source as appropriate
					source.setCreated(oldSource.getCreated());
					source.setModified(new Date());
					source.setOwnerId(oldSource.getOwnerId());
					
					if (null == source.getIsPublic()) {
						source.setIsPublic(oldSource.getIsPublic());
					}//TESTED

					// Harvest status specification logic (we need normally need to keep these fields intact):
					// - If harvest completely unspecified, delete everything but num records
					// - If harvest specified, and there exists an existing harvest block then ignore
					// - If harvest specified, and the harvest has previously been deleted, then copy (except num records)
					// - Extra ... if new status object has harvested unset, then unset that
					if ((null == source.getHarvestStatus()) && (null != oldSource.getHarvestStatus())) { 
						// request to unset the harvest status altogether
						source.setHarvestStatus(new SourceHarvestStatusPojo()); // new harvest status
						source.getHarvestStatus().setDoccount(oldSource.getHarvestStatus().getDoccount());
							// but keep number of records
					}
					else if ((null != oldSource.getHarvestStatus()) && (null == oldSource.getHarvestStatus().getHarvest_status())) {
						// Has previously been unset with the logic from the above clause
						source.getHarvestStatus().setDoccount(oldSource.getHarvestStatus().getDoccount());
							// (null != source.getHarvestStatus()) else would be in the clause above
					}
					else if (null != oldSource.getHarvestStatus()) {
						// Unset the harvested time to queue a new harvest cycle
						if ((null != source.getHarvestStatus()) && (null == source.getHarvestStatus().getHarvested())) {
							oldSource.getHarvestStatus().setHarvested(null);
						}
						source.setHarvestStatus(oldSource.getHarvestStatus());
					}
					//(else oldSource.getHarvestStatus is null, just retain the updated version)
					
					//TESTED: no original harvest status, failing to edit existing harvest status, delete status (except doc count), update deleted status (except doc count)
					
					// If we're changing the distribution factor, need to keep things a little bit consistent:
					if ((null == source.getDistributionFactor()) && (null != oldSource.getDistributionFactor())) {
						// Removing it:
						if (null != source.getHarvestStatus()) {
							source.getHarvestStatus().setDistributionReachedLimit(null);
							source.getHarvestStatus().setDistributionTokensComplete(null);
							source.getHarvestStatus().setDistributionTokensFree(null);							
						}
					}//TESTED
					else if ((null != source.getDistributionFactor()) && (null != oldSource.getDistributionFactor())
							&& (source.getDistributionFactor() != oldSource.getDistributionFactor()))
					{
						// Update the number of available tokens:
						if ((null != source.getHarvestStatus()) && (null != source.getHarvestStatus().getDistributionTokensFree()))
						{
							int n = source.getHarvestStatus().getDistributionTokensFree() + 
										(source.getDistributionFactor() - oldSource.getDistributionFactor());
							if (n < 0) n = 0;
							
							source.getHarvestStatus().setDistributionTokensFree(n);
						}
					}//TESTED
					
					///////////////////////////////////////////////////////////////////////
					// Check for missing fields:
					String missingFields = hasRequiredSourceFields(source);
					if (missingFields != null && missingFields.length() > 0)
					{
						rp.setResponse(new ResponseObject("Source", false, missingFields));
						return rp;
					}
					
					///////////////////////////////////////////////////////////////////////
					// Note: Create/update the source's Shah-256 hash
					source.generateShah256Hash();
					
					///////////////////////////////////////////////////////////////////////
					// Handle approval:
					if (isApproved || oldHash.equalsIgnoreCase(source.getShah256Hash())) {
						//(either i have permissions, or the source hasn't change)
						// if source is unset then isApproved == false
						// but that's pretty unlikely since the user would have had to hand-remove it
						// so just leave source.isApproved alone
					}
					else { // Need to re-approve						
						try {
							source.setApproved(false);
							emailSourceApprovalRequest(source);
						}
						catch (Exception e) { // Unable to ask for permission, remove sources and error out
							logger.error("Exception Message: " + e.getMessage(), e);
							DbManager.getIngest().getSource().remove(new BasicDBObject(SourcePojo._id_, source.getId()));
							rp.setData((String)null, (BasePojoApiMap<String>)null); // (unset)
							rp.setResponse(new ResponseObject("Source", false, "Unable to email authority for permission, maybe email infrastructure isn't added? Error: " + e.getMessage()));
						}
					}//TOTEST					
					
					if (isApproved) { // (this activity can result in custom activity, so don't do it unless the source is approved)
						try {
							new DistributedHarvester().decomposeCustomSourceIntoMultipleJobs(source, true);
						}
						catch (Throwable t) {
							rp.setResponse(new ResponseObject("Source", false, "Error updating custom source: " + t.getMessage()));
							return rp;
						}//TESTED (by hand)
					}					
					// Source exists, update and prepare reply
					DbManager.getIngest().getSource().update(query, source.toDb());
					if (isUniqueSource(source, communityIdSet))
					{
						rp.setResponse(new ResponseObject("Source", true, "Source has been updated successfully."));
					}
					else { // Still allow people to add identical sources, but warn them so they can delete it if they way
						rp.setResponse(new ResponseObject("Source", true, "Source has been updated successfully. Note functionally identical sources are also present within your communities, which may waste system resources."));
					}
					rp.setData(source, new SourcePojoApiMap(null, communityIdSet, null));
				} 
				catch (Exception e) 
				{
					logger.error("Exception Message: " + e.getMessage(), e);
					rp.setResponse(new ResponseObject("Source", false, "Unable to update source: " + e.getMessage()));
				}
			}
		}
		catch (Exception e) {
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Source", false, "Unable to add/update source: " + e.getMessage()));			
		}
		return rp;
	}
	
	public static boolean sourceSearchesWeb(SourcePojo source) {
		if ((null != source.getRssConfig()) && (null != source.getRssConfig().getSearchConfig())) {
			return true;
		}
		else if (null != source.getProcessingPipeline() && source.getExtractType().equalsIgnoreCase("feed")) {
			for (SourcePipelinePojo pxPipe: source.getProcessingPipeline()) {
				if (null != pxPipe.links) {
					return true;
				}
			}
		}
		return false;
	}//TESTED (by hand, pipeline and non pipeline modes; in pipeline mode have tested both feed and non-feed modes)
		
	/**
	 * deleteSource
	 * @param sourceIdStr
	 * @return
	 */
	public ResponsePojo deleteSource(String sourceIdStr, String communityIdStr, String personIdStr, boolean bDocsOnly) {
		ResponsePojo rp = new ResponsePojo();	
		try {
			if (!ElasticSearchManager.pingCluster()) {
				rp.setResponse(new ResponseObject("Delete Source", false, "Index not running."));			
				return rp;
			}//TESTED (by hand)
			
			communityIdStr = allowCommunityRegex(personIdStr, communityIdStr);
			boolean isApproved = isOwnerModeratorOrSysAdmin(communityIdStr, personIdStr);
			ObjectId communityId = new ObjectId(communityIdStr);
			// (Can't use pojos for queries because sources currently have default fields)
			BasicDBObject queryDbo = new BasicDBObject(SourcePojo.communityIds_, communityId);
			BasicDBObject queryFields = new BasicDBObject(SourcePojo.key_, 1);
			queryFields.put(SourcePojo.extractType_, 1);
			queryFields.put(SourcePojo.harvest_, 1);
			queryFields.put(SourcePojo.ownerId_, 1);
			queryFields.put(SourcePojo.distributionFactor_, 1);
			queryFields.put(SourcePojo.processingPipeline_ + ".logstash.distributed", 1); // (this is needed to determine distribution status)
			try {
				queryDbo.put(SourcePojo._id_, new ObjectId(sourceIdStr));
			}
			catch (Exception e) { // (allow either _id or key)
				queryDbo.put(SourcePojo.key_, sourceIdStr);					
			}
			if (!isApproved) {
				queryDbo.put(SourcePojo.ownerId_, new ObjectId(personIdStr));
			}
			if (!bDocsOnly) {
				BasicDBObject turnOff = new BasicDBObject(MongoDbManager.set_, 
											new BasicDBObject(SourcePojo.isApproved_, false));
				DbManager.getIngest().getSource().update(queryDbo, turnOff);
			}
			BasicDBObject srcDbo = (BasicDBObject) DbManager.getIngest().getSource().findOne(queryDbo, queryFields);
			if (null == srcDbo) {
				rp.setResponse(new ResponseObject("Delete Source", false, "Error finding source or permissions error."));			
				return rp;
			}
			// OK if we've got to here we're approved and the source exists, start deleting stuff
			SourcePojo source = SourcePojo.fromDb(srcDbo, SourcePojo.class);
			
			//double check this source isn't running INF-2195
			long nDocsDeleted = 0;
			if ( null != source.getHarvestStatus() )
			{
				//a source is in progress if its status is in progress or
				//its distribution factor does not equals its free dist tokens
				if ( (source.getHarvestStatus().getHarvest_status() == HarvestEnum.in_progress ) || 
					(null != source.getDistributionFactor() && source.getDistributionFactor() != source.getHarvestStatus().getDistributionTokensFree() ) )
				{
					if (!bDocsOnly) { // (otherwise we'll allow it, shouldn't cause _too_ many probs)
						rp.setResponse(new ResponseObject("Delete Source", false, "Source is still in progress, ignored - suspend the source and then try again once complete"));	
						return rp;
					}//TESTED - delete docs works, delete source fails
				}
				if (null != source.getHarvestStatus().getDoccount()) {
					nDocsDeleted = source.getHarvestStatus().getDoccount();
				}//TESTED
			}			
			
			if (null != source.getKey()) { // or may delete everything!
				
				if (bDocsOnly) { // Update the source harvest status (easy: no documents left!)
					try {
						DbManager.getIngest().getSource().update(queryDbo,
								new BasicDBObject(MongoDbManager.set_, 
										new BasicDBObject(SourceHarvestStatusPojo.sourceQuery_doccount_, 0L))
								);
					}
					catch (Exception e) {} // Just carry on, shouldn't ever happen and it's too late to do anything about it
					//TESTED					
				}
				else { // !bDocsOnly, ie delete source also
					DbManager.getIngest().getSource().remove(queryDbo);					
				}
				
				// More complex processing:
				
				if ((null != source.getExtractType()) && source.getExtractType().equalsIgnoreCase("logstash")) {
					BasicDBObject logStashMessage = new BasicDBObject();
					logStashMessage.put("_id", source.getId());
					logStashMessage.put("deleteOnlyCommunityId", communityId);
					logStashMessage.put("sourceKey", source.getKey());
					logStashMessage.put("deleteDocsOnly", bDocsOnly);
					
					if ((null != source.getProcessingPipeline()) && !source.getProcessingPipeline().isEmpty()) {
						SourcePipelinePojo px = source.getProcessingPipeline().iterator().next();
						if ((null != px.logstash) && (null != px.logstash.distributed) && px.logstash.distributed) {
							logStashMessage.put("distributed", true);
						}
					}//TESTED (by hand)
					DbManager.getIngest().getLogHarvesterQ().save(logStashMessage);
					// (the rest of this is async, so we're done here)
				}//TESTED	
				//v2 source
				if ((null != source.getExtractType()) && source.getExtractType().equalsIgnoreCase("v2databucket")
						&& bDocsOnly) {
					//only do this if we are deleting docs, bucket deletion is picked up via the bucket sync service instead
					//of the purge service
					//Need to requery for full source object
					DBObject fullSrcDbo =  DbManager.getIngest().getSource().findOne(queryDbo);
					SourcePojo full_source = SourcePojo.fromDb(fullSrcDbo, SourcePojo.class);
					BasicDBObject v2Message = new BasicDBObject();
					v2Message.put("_id", full_source.getId());
					v2Message.put("source", full_source.toDb());
					v2Message.put("status", "submitted");
		
					// Step 0: place request on Q
					DbManager.getIngest().getV2DataBucketPurgeQ().save(v2Message);														
					// (the rest of this is async, so we're done here)
				}
				if ((null != source.getExtractType()) && 
						(source.getExtractType().equalsIgnoreCase("post_processing")||
									source.getExtractType().equalsIgnoreCase("custom")||
									source.getExtractType().equalsIgnoreCase("distributed")))
				{
					//TODO: yuri somehow managed to delete a source without deleting the corresponding custom
					// (might have been in progress, which in theory should fail but haven't tested...)
					
					// Various distributed cases
					//TODO (INF-2866) at some point will need to handle sources generating multiple custom objects 
					// (also having actual docs being generated by a custom processing object)
					// (...but today is not that day...)
					ResponsePojo rp2 = CustomHandler.removeJob(source.getOwnerId().toString(), source.getKey(), null, false);
					if (!rp2.getResponse().isSuccess()) {
						rp.setResponse(rp.getResponse());
						rp.getResponse().setAction("Delete Source");
						return rp;
					}
					if (bDocsOnly) { // Put the source back again
						try {
							source = SourcePojo.fromDb(DbManager.getIngest().getSource().findOne(queryDbo), SourcePojo.class);
							if (source.isApproved()) {
								if (null != source.getHarvestStatus()) {
									source.getHarvestStatus().setHarvest_status(null);
								}
								new DistributedHarvester().decomposeCustomSourceIntoMultipleJobs(source, false);
							}
						}
						catch (Throwable t) {
							rp.setResponse(new ResponseObject("Delete Source", false, Globals.populateStackTrace(new StringBuffer("Error re-creating custom source: "), t).toString()));
							return rp;								
						}							
					}//TESTED (by hand)
					
				}//TESTED (by hand)
				else { // Not logstash or distributed, ie "normal" document case
					// Insert delete message on distributed Q
					SourcePojo sourceDeletionMessage = new SourcePojo();
					sourceDeletionMessage.setKey(source.getKey());
					sourceDeletionMessage.setCommunityIds(new HashSet<ObjectId>((Collection<ObjectId>) Arrays.asList(communityId)));
					DbManager.getIngest().getSourceDeletionQ().save(sourceDeletionMessage.toDb());														
				}//TESTED
			}	
			else if (!bDocsOnly) { // (null source key, just remove the source)
				DbManager.getIngest().getSource().remove(queryDbo);				
			}
			if (!bDocsOnly) { // Also deleting the entire source
				rp.setResponse(new ResponseObject("Delete Source", true, "Deleted source and all documents: " + nDocsDeleted));			
			}
			else { 				
				rp.setResponse(new ResponseObject("Delete Source", true, "Deleted source documents: " + nDocsDeleted));						
			}
		}
		catch (Exception e) {
			//DEBUG
			//e.printStackTrace();
			rp.setResponse(new ResponseObject("Delete Source", false, 
			"Error deleting source. You must be a the owner, the community owner or a moderator to delete the source: " + e.getMessage()));			
		}
		return rp;

	}//TESTED
	
	/**
	 * approveSource
	 * @param sourceIdStr
	 * @param communityIdStrList
	 * @return
	 */
	public ResponsePojo approveSource(String sourceIdStr, String communityIdStr, String submitterId) 
	{
		ResponsePojo rp = new ResponsePojo();	

		try 
		{
			Set<ObjectId> communityIdSet = new TreeSet<ObjectId>();
			ObjectId communityId = new ObjectId(communityIdStr);
			communityIdSet.add(communityId);

			BasicDBObject query = new BasicDBObject();
			try {
				query.put(SourcePojo._id_, new ObjectId(sourceIdStr));
			}
			catch (Exception e) { // (allow either _id or key)
				query.put(SourcePojo.key_, sourceIdStr);					
			}
			query.put(SourcePojo.communityIds_, communityId);

			DBObject dbo = (BasicDBObject)DbManager.getIngest().getSource().findOne(query);
			SourcePojo sp = SourcePojo.fromDb(dbo,SourcePojo.class);
			sp.setApproved(true);

			DbManager.getIngest().getSource().update(query, (DBObject) sp.toDb());
			rp.setData(sp, new SourcePojoApiMap(null, communityIdSet, null));
			rp.setResponse(new ResponseObject("Approve Source",true,"Source approved successfully"));
			
			// Send email notification to the person who submitted the source
			emailSourceApproval(sp, submitterId, "Approved");
		} 
		catch (Exception e)
		{
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Approve Source",false,"Error approving source"));
		}		

		return rp;
	}
	
	/**
	 * denySource
	 * @param sourceIdStr
	 * @param communityIdStrList
	 * @return
	 */
	public ResponsePojo denySource(String sourceIdStr, String communityIdStr, String submitterId) 
	{
		ResponsePojo rp = new ResponsePojo();	

		try 
		{
			Set<ObjectId> communityIdSet = new TreeSet<ObjectId>();
			ObjectId communityId = new ObjectId(communityIdStr);
			communityIdSet.add(communityId);
			
			// Set up the query
			BasicDBObject query = new BasicDBObject();
			try {
				query.put(SourcePojo._id_, new ObjectId(sourceIdStr));
			}
			catch (Exception e) { // (allow either _id or key)
				query.put(SourcePojo.key_, sourceIdStr);					
			}
			query.put(SourcePojo.communityIds_, communityId);

			// Get the source - what we do with it depends on whether it's ever been active or not
			DBObject dbo = (BasicDBObject)DbManager.getIngest().getSource().findOne(query);
			SourcePojo sp = SourcePojo.fromDb(dbo,SourcePojo.class);
			
			// Case 1: is currently active, set to inactive
			
			if (sp.isApproved()) {
				sp.setApproved(false);
				DbManager.getIngest().getSource().update(query, (DBObject) sp.toDb());
				rp.setResponse(new ResponseObject("Decline Source",true,"Source set to unapproved, use config/source/delete to remove it"));
			}
			
			// Case 2: is currently inactive, has been active
			
			else if (null != sp.getHarvestStatus()) {
				rp.setResponse(new ResponseObject("Decline Source",false,"Source has been active, use config/source/delete to remove it"));
			}
			
			// Case 3: 

			else {
				DbManager.getIngest().getSource().remove(query);			
				rp.setResponse(new ResponseObject("Deny Source",true,"Source removed successfully"));					
				// Send email notification to the person who submitted the source
				emailSourceApproval(getSource(sourceIdStr), submitterId, "Denied");
			}
							
		} 
		catch (Exception e)
		{
			// If an exception occurs log the error
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Deny Source",false,"error removing source"));
		}
		return rp;
	}
	
	///////////////////////////////////////////////////////////////////////////////////////////////
	
	// BULK SOURCE ACCESS
	
	/**
	 * getGoodSources
	 * Get a list of approved sources for a list of one or more
	 * community IDs passed via the communityid parameter
	 * @param communityIdStrList
	 * @return
	 */
	public ResponsePojo getGoodSources(String userIdStr, String communityIdStrList, boolean bStrip) 
	{
		ResponsePojo rp = new ResponsePojo();		
		try 
		{
			String[] communityIdStrs = SocialUtils.getCommunityIds(userIdStr, communityIdStrList);
			ObjectId userId = null;
			boolean bAdmin = RESTTools.adminLookup(userIdStr);
			if (!bAdmin) {
				userId = new ObjectId(userIdStr); // (ie not admin, may not see 
			}
			Set<ObjectId> communityIdSet = new TreeSet<ObjectId>();
			Set<ObjectId> ownedOrModeratedCommunityIdSet = new TreeSet<ObjectId>();
			for (String s: communityIdStrs) {
				ObjectId communityId = new ObjectId(s); 
				communityIdSet.add(communityId);
				if (null != userId) {
					if (isOwnerOrModerator(communityId.toString(), userIdStr)) {
						ownedOrModeratedCommunityIdSet.add(communityId);													
					}					
				}
			}
			//TESTED (owner and community owner, public and not public) 
			
			// Set up the query
			BasicDBObject query = new BasicDBObject();
			// (allow failed harvest sources because they might have previously had good data)
			query.put(SourcePojo.isApproved_, true);
			query.put(SourcePojo.communityIds_, new BasicDBObject(MongoDbManager.in_, communityIdSet));
			BasicDBObject fields = new BasicDBObject();
			if (bStrip) {
				setStrippedFields(fields);
			}			
			
			DBCursor dbc = DbManager.getIngest().getSource().find(query, fields);
			
			// Remove communityids we don't want the user to see:
			if (bStrip && sanityCheckStrippedSources(dbc.toArray(), bAdmin)) {
				rp.setData(dbc.toArray(), (BasePojoApiMap<DBObject>)null);
			}
			else {
				rp.setData(SourcePojo.listFromDb(dbc, SourcePojo.listType()), new SourcePojoApiMap(userId, communityIdSet, ownedOrModeratedCommunityIdSet));
			}
			rp.setResponse(new ResponseObject("Good Sources",true,"successfully returned good sources"));
		} 
		catch (Exception e)
		{
			// If an exception occurs log the error
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Good Sources",false,"error returning good sources"));			
		}
		// Return Json String representing the user
		return rp;
	}
		
	/**
	 * getBadSources
	 * Get a list of sources with harvester errors for a list of one or more
	 * community IDs passed via the communityid parameter
	 * @param communityIdStrList
	 * @return
	 */
	public ResponsePojo getBadSources(String userIdStr, String communityIdStrList, boolean bStrip) 
	{
		ResponsePojo rp = new ResponsePojo();
		try 
		{
			String[] communityIdStrs = SocialUtils.getCommunityIds(userIdStr, communityIdStrList);
			ObjectId userId = null;
			boolean bAdmin = RESTTools.adminLookup(userIdStr);
			if (!bAdmin) {
				userId = new ObjectId(userIdStr); // (ie not admin, may not see 
			}
			Set<ObjectId> communityIdSet = new TreeSet<ObjectId>();
			Set<ObjectId> ownedOrModeratedCommunityIdSet = new TreeSet<ObjectId>();
			for (String s: communityIdStrs) {
				ObjectId communityId = new ObjectId(s); 
				communityIdSet.add(communityId);
				if (null != userId) {
					if (isOwnerOrModerator(communityId.toString(), userIdStr)) {
						ownedOrModeratedCommunityIdSet.add(communityId);													
					}					
				}
			}
			//TESTED (owner and community owner, public and not public) 
			
			// Set up the query
			BasicDBObject query = new BasicDBObject();
			query.put(SourceHarvestStatusPojo.sourceQuery_harvest_status_, HarvestEnum.error.toString());
			query.put(SourcePojo.communityIds_, new BasicDBObject(MongoDbManager.in_, communityIdSet));
			BasicDBObject fields = new BasicDBObject();
			if (bStrip) {
				setStrippedFields(fields);
			}			
			DBCursor dbc = DbManager.getIngest().getSource().find(query, fields);			

			// Remove communityids we don't want the user to see:
			if (bStrip && sanityCheckStrippedSources(dbc.toArray(), bAdmin)) {
				rp.setData(dbc.toArray(), (BasePojoApiMap<DBObject>)null);
			}
			else {
				rp.setData(SourcePojo.listFromDb(dbc, SourcePojo.listType()), new SourcePojoApiMap(userId, communityIdSet, ownedOrModeratedCommunityIdSet));
			}
			rp.setResponse(new ResponseObject("Bad Sources",true,"Successfully returned bad sources"));
		} 
		catch (Exception e)
		{
			// If an exception occurs log the error
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Bad Sources",false,"error returning bad sources"));
		}
		// Return Json String representing the user
		return rp;
	}
	
	
	/**
	 * getPendingSources
	 * Get a list of sources pending approval for a list of one or more
	 * community IDs passed via the communityid parameter
	 * @param communityIdStrList
	 * @return
	 */
	public ResponsePojo getPendingSources(String userIdStr, String communityIdStrList, boolean bStrip) 
	{
		ResponsePojo rp = new ResponsePojo();		
		try 
		{
			String[] communityIdStrs = SocialUtils.getCommunityIds(userIdStr, communityIdStrList);
			ObjectId userId = null;
			boolean bAdmin = RESTTools.adminLookup(userIdStr);
			if (!bAdmin) {
				userId = new ObjectId(userIdStr); // (ie not admin, may not see 
			}
			Set<ObjectId> communityIdSet = new TreeSet<ObjectId>();
			Set<ObjectId> ownedOrModeratedCommunityIdSet = new TreeSet<ObjectId>();
			for (String s: communityIdStrs) {
				ObjectId communityId = new ObjectId(s); 
				communityIdSet.add(communityId);
				if (null != userId) {
					if (isOwnerOrModerator(communityId.toString(), userIdStr)) {
						ownedOrModeratedCommunityIdSet.add(communityId);													
					}					
				}
			}
			//TESTED (owner and community owner, public and not public) 
			
			// Set up the query
			BasicDBObject query = new BasicDBObject();
			query.put(SourcePojo.isApproved_, false);
			query.put(SourcePojo.communityIds_, new BasicDBObject(MongoDbManager.in_, communityIdSet));
			BasicDBObject fields = new BasicDBObject();
			if (bStrip) {
				setStrippedFields(fields);
			}			
			DBCursor dbc = DbManager.getIngest().getSource().find(query, fields);
			
			// Remove communityids we don't want the user to see:
			if (bStrip && sanityCheckStrippedSources(dbc.toArray(), bAdmin)) {
				rp.setData(dbc.toArray(), (BasePojoApiMap<DBObject>)null);
			}
			else {
				rp.setData(SourcePojo.listFromDb(dbc, SourcePojo.listType()), new SourcePojoApiMap(userId, communityIdSet, ownedOrModeratedCommunityIdSet));
			}
			rp.setResponse(new ResponseObject("Pending Sources",true,"successfully returned pending sources"));
		} 
		catch (Exception e)
		{
			// If an exception occurs log the error
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Pending Sources",false,"error returning pending sources"));			
		}
		return rp;
	}
	
	
	/**
	 * getUserSources
	 * @param userIdStr
	 * @param userId
	 * @return
	 */
	public ResponsePojo getUserSources(String userIdStr, boolean bStrip, boolean bCommunityFilter, Boolean bUserFilter) 
	{
		ResponsePojo rp = new ResponsePojo();
		try 
		{	
			boolean bAdmin = RESTTools.adminLookup(userIdStr);
			boolean userFilter = Optional.ofNullable(bUserFilter).orElse(!bAdmin);
			HashSet<ObjectId> userCommunities = SocialUtils.getUserCommunities(userIdStr);
			
			DBCursor dbc = null;
			BasicDBObject query = new BasicDBObject();
			if (!bAdmin || bCommunityFilter) {
				query.put(SourcePojo.communityIds_, new BasicDBObject(MongoDbManager.in_, userCommunities));
			}
			BasicDBObject fields = new BasicDBObject();
			if (bStrip) {
				setStrippedFields(fields);
			}			
								
			Set<ObjectId> ownedOrModeratedCommunityIdSet = null;
			if (!bAdmin) {
				ownedOrModeratedCommunityIdSet = new TreeSet<ObjectId>();
				for (ObjectId communityId: userCommunities) {
					if (isOwnerOrModerator(communityId.toString(), userIdStr)) {
						ownedOrModeratedCommunityIdSet.add(communityId);													
					}					
				}
			}
			// Get all selected sources for admins (if user filter disabled) 
			if (!userFilter)
			{
				dbc = DbManager.getIngest().getSource().find(query, fields);
			}
			// Get only sources the user owns or owns/moderates the parent community
			else
			{
				query.put(SourcePojo.ownerId_, new ObjectId(userIdStr));
				BasicDBObject query2 = new BasicDBObject();
				query2.put(SourcePojo.communityIds_, new BasicDBObject(MongoDbManager.in_, ownedOrModeratedCommunityIdSet));
				dbc = DbManager.getIngest().getSource().find(new BasicDBObject(MongoDbManager.or_, Arrays.asList(query, query2)), fields);
			}			
			if (bStrip && sanityCheckStrippedSources(dbc.toArray(), bAdmin)) {
				rp.setData(dbc.toArray(), (BasePojoApiMap<DBObject>)null);
			}
			else {
				rp.setData(SourcePojo.listFromDb(dbc, SourcePojo.listType()), new SourcePojoApiMap(null, userCommunities, null));
			}
			rp.setResponse(new ResponseObject("User's Sources", true, "successfully returned user's sources"));
		} 
		catch (Exception e)
		{
			// If an exception occurs log the error
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("User's Sources", false, "error returning user's sources"));			
		}
		return rp;
	}


	// Source performance utils
	
	private static void setStrippedFields(BasicDBObject fields) {
		fields.put(SourcePojo.created_, 1);
		fields.put(SourcePojo.modified_, 1);
		fields.put(SourcePojo.url_, 1);
		fields.put(SourcePojo.title_, 1);
		fields.put(SourcePojo.isPublic_, 1);
		fields.put(SourcePojo.ownerId_, 1);
		fields.put(SourcePojo.author_, 1);
		fields.put(SourcePojo.mediaType_, 1);
		fields.put(SourcePojo.key_, 1);
		fields.put(SourcePojo.description_, 1);
		fields.put(SourcePojo.distributionFactor_, 1);
		fields.put(SourcePojo.tags_, 1);
		fields.put(SourcePojo.communityIds_, 1);
		fields.put(SourcePojo.harvest_, 1);
		fields.put(SourcePojo.isApproved_, 1);
		fields.put(SourcePojo.extractType_, 1);
		fields.put(SourcePojo.searchCycle_secs_, 1);
		fields.put(SourcePojo.maxDocs_, 1);
		fields.put(SourcePojo.duplicateExistingUrls_, 1);
	}//TESTED
	
	private static boolean sanityCheckStrippedSources(List<DBObject> dbc, boolean bAdmin)
	{
		bAdmin = false;
		if (bAdmin) {
			return true;
		}
		else {
			for (DBObject dbo: dbc) {
				BasicDBList commList = (BasicDBList) dbo.get(SourcePojo.communityIds_);
				if (null != commList) {
					if (commList.size() > 1) {
						return false;
					}
				}
			}
		}
		return true;
	}//TESTED (bAdmin false and true)

	///////////////////////////////////////////////////////////////////////////////////////////////
	
	// SOURCE TEST CODE
		
	/**
	 * testSource
	 * @param sourceJson
	 * @param nNumDocsToReturn
	 * @param bReturnFullText
	 * @param userIdStr
	 * @return
	 */
	public ResponsePojo testSource(String sourceJson, int nNumDocsToReturn, boolean bReturnFullText, boolean bRealDedup, String userIdStr) 
	{
		ResponsePojo rp = new ResponsePojo();		
		try 
		{
			SourcePojo source = null;
			SourcePojoSubstitutionApiMap apiMap = new SourcePojoSubstitutionApiMap(new ObjectId(userIdStr));
			try {
				source = ApiManager.mapFromApi(sourceJson, SourcePojo.class, apiMap);
				source.fillInSourcePipelineFields();
			}
			catch (Exception e) {
				rp.setResponse(new ResponseObject("Test Source",false,"Error deserializing source (JSON is valid but does not match schema): " + e.getMessage()));						
				return rp;				
			}
			if (null == source.getKey()) {
				source.setKey(source.generateSourceKey()); // (a dummy value, not guaranteed to be unique)
			}
			if ((null == source.getExtractType()) || !source.getExtractType().equals("Federated")) {
				String testUrl = source.getRepresentativeUrl();
				if (null == testUrl) {
					rp.setResponse(new ResponseObject("Test Source",false,"Error, source contains no URL to harvest"));						
					return rp;								
				}
			}
			if (null == source.getTags()) {
				source.setTags(new HashSet<String>());
			}
			
			// This is the only field that you don't normally need to specify in save but will cause 
			// problems if it's not populated in test.
			ObjectId userId = new ObjectId(userIdStr);
			// Set owner (overwrite, for security reasons)
			source.setOwnerId(userId);
			if (null == source.getCommunityIds()) {
				source.setCommunityIds(new TreeSet<ObjectId>());
			}
			if (!source.getCommunityIds().isEmpty()) { // need to check that I'm allowed the specified community...
				if ((1 == source.getCommunityIds().size()) && (userId.equals(source.getCommunityIds().iterator().next())))
				{
					// we're OK only community id is user community
				}//TESTED
				else {
					HashSet<ObjectId> communities = SocialUtils.getUserCommunities(userIdStr);
					Iterator<ObjectId> it = source.getCommunityIds().iterator();
					while (it.hasNext()) {
						ObjectId src = it.next();
						if (!communities.contains(src)) {
							rp.setResponse(new ResponseObject("Test Source",false,"Authentication error: you don't belong to this community: " + src));						
							return rp;
						}//TESTED
					}
				}//TESTED
			}
			// Always add the userId to the source community Id (so harvesters can tell if they're running in test mode or not...) 
			source.addToCommunityIds(userId); // (ie user's personal community, always has same _id - not that it matters)
			
			// Check the source's admin status
			source.setOwnedByAdmin(RESTTools.adminLookup(userId.toString(), false));			
			
			if (bRealDedup) { // Want to test update code, so ignore update cycle
				if (null != source.getRssConfig()) {
					source.getRssConfig().setUpdateCycle_secs(1); // always update
				}
			}
			HarvestController harvester = new HarvestController(true);
			if (nNumDocsToReturn > 100) { // (seems reasonable)
				nNumDocsToReturn = 100;
			}
			harvester.setStandaloneMode(nNumDocsToReturn, bRealDedup);
			List<DocumentPojo> toAdd = new LinkedList<DocumentPojo>();
			List<DocumentPojo> toUpdate = new LinkedList<DocumentPojo>();
			List<DocumentPojo> toRemove = new LinkedList<DocumentPojo>();
			if (null == source.getHarvestStatus()) {
				source.setHarvestStatus(new SourceHarvestStatusPojo());
			}
			String oldMessage = source.getHarvestStatus().getHarvest_message();
			// SPECIAL CASE: FOR FEDERATED QUERIES
			if ((null != source.getExtractType()) && source.getExtractType().equals("Federated")) {
				int federatedQueryEnts = 0;
				SourceFederatedQueryConfigPojo endpoint = null;
				try {
					endpoint = source.getProcessingPipeline().get(0).federatedQuery;
				}
				catch (Exception e) {}
				if (null == endpoint) {
					rp.setResponse(new ResponseObject("Test Source",false,"source error: no federated query specified"));			
					return rp;
				}
				AdvancedQueryPojo testQuery = null;
				String errMessage = "no query specified";
				try {
					testQuery = AdvancedQueryPojo.fromApi(endpoint.testQueryJson, AdvancedQueryPojo.class);
				}
				catch (Exception e) {
					errMessage = e.getMessage();
				}
				if (null == testQuery) {
					rp.setResponse(new ResponseObject("Test Source",false,"source error: need to specifiy a valid IKANOW query to test federated queries, error: " + errMessage));			
					return rp;					
				}
				// OK if we're here then we can test the query
				SimpleFederatedQueryEngine testFederatedQuery = new SimpleFederatedQueryEngine();
				endpoint.parentSource = source;
				testFederatedQuery.addEndpoint(endpoint);
				ObjectId queryId = new ObjectId();
				String[] communityIdStrs = new String[source.getCommunityIds().size()];
				int i = 0;
				for (ObjectId commId: source.getCommunityIds()) {
					communityIdStrs[i] = commId.toString();
					i++;
				}
				testFederatedQuery.setTestMode(true);
				testFederatedQuery.preQueryActivities(queryId, testQuery, communityIdStrs);
				StatisticsPojo stats = new StatisticsPojo();
				stats.setSavedScores(0, 0);
				rp.setStats(stats);
				ArrayList<BasicDBObject> toAddTemp = new ArrayList<BasicDBObject>(1);
				testFederatedQuery.postQueryActivities(queryId, toAddTemp, rp);
				for (BasicDBObject docObj: toAddTemp) {
					DocumentPojo doc = DocumentPojo.fromDb(docObj, DocumentPojo.class);
					if (bReturnFullText) {
						doc.setFullText(docObj.getString(DocumentPojo.fullText_));
						doc.makeFullTextNonTransient();
					}
					if (null != doc.getEntities()) {
						federatedQueryEnts += doc.getEntities().size();
					}
					
					//Metadata workaround:
					@SuppressWarnings("unchecked")
					LinkedHashMap<String, Object[]> meta = (LinkedHashMap<String, Object[]>) docObj.get(DocumentPojo.metadata_);
					if (null != meta) {
						Object metaJson = meta.get("json");
						if (metaJson instanceof Object[]) { // (in this case ... non-cached, need to recopy in, I forget why)
							doc.addToMetadata("json", (Object[])metaJson);
						}
					}					
					toAdd.add(doc);
				}
				// (currently can't run harvest source federated query)
				if (0 == federatedQueryEnts) { // (more fed query exceptions)
					source.getHarvestStatus().setHarvest_message("Warning: no entities extracted, probably docConversionMap is wrong?");
				}
				else {
					source.getHarvestStatus().setHarvest_message(federatedQueryEnts + " entities extracted");
				}
				
			}//TESTED (END FEDERATED QUERY TEST MODE, WHICH IS A BIT DIFFERENT)
			else {
				harvester.harvestSource(source, toAdd, toUpdate, toRemove);
			}			
			
			// (don't parrot the old message back - v confusing)
			if (oldMessage == source.getHarvestStatus().getHarvest_message()) { // (ptr ==)
				source.getHarvestStatus().setHarvest_message("(no documents extracted - likely a source or configuration error)");				
			}//TESTED
			
			String message = null;
			if ((null != source.getHarvestStatus()) && (null != source.getHarvestStatus().getHarvest_message())) {
				message = source.getHarvestStatus().getHarvest_message();
			}
			else {
				message = "";
			}
			List<String> errMessagesFromSourceDeser = apiMap.getErrorMessages();
			if (null != errMessagesFromSourceDeser) {
				StringBuffer sbApiMapErr = new StringBuffer("Substitution errors:\n"); 
				for (String err: errMessagesFromSourceDeser) {
					sbApiMapErr.append(err).append("\n");
				}
				message = message + "\n" + sbApiMapErr.toString();
			}//TESTED (by hand)
			
			if ((null != source.getHarvestStatus()) && (HarvestEnum.error == source.getHarvestStatus().getHarvest_status())) {
				rp.setResponse(new ResponseObject("Test Source",false,"source error: " + message));			
				rp.setData(toAdd, new DocumentPojoApiMap());				
			}
			else {
				if ((null == message) || message.isEmpty()) {
					message = "no messages from harvester";
				}
				rp.setResponse(new ResponseObject("Test Source",true,"successfully returned " + toAdd.size() + " docs: " + message));			
				try {
					// If grabbing full text
					// Also some logstash/custom specific logic - these aren't docs so just output the entire record
					boolean isLogstash = (null != source.getExtractType()) && source.getExtractType().equalsIgnoreCase("logstash");
					boolean isCustom = (null != source.getExtractType()) && source.getExtractType().equalsIgnoreCase("custom");
					boolean isV2 = (null != source.getExtractType()) && source.getExtractType().equalsIgnoreCase("v2databucket");
					List<BasicDBObject> records = null;
					if (bReturnFullText || isLogstash || isCustom || isV2) {
						for (DocumentPojo doc: toAdd) {
							if (isLogstash || isCustom || isV2) {
								if (null == records) {
									records = new ArrayList<BasicDBObject>(toAdd.size());									
								}
								BasicDBObject dbo = (BasicDBObject) doc.getMetadata().get("record")[0];
								Object test = dbo.get("_id");
								if ((null != test) && (test instanceof ObjectId)) {
									dbo.remove("_id"); // (unless it's a custom _id added from logstash then remove it)
								}
								records.add(dbo);
							}//TESTED
							else if (bReturnFullText) {
								doc.makeFullTextNonTransient();
							}
						}
					}//TESTED
					if (null != records) {
						rp.setData(records, (BasePojoApiMap<BasicDBObject>)null);						
					}//TESTED
					else {
						rp.setData(toAdd, new DocumentPojoApiMap());
					}//TESTED
					
					//Test deserialization:
					rp.toApi();
				}
				catch (Exception e) {
					//e.printStackTrace();
					StringBuffer sb = new StringBuffer();
					Globals.populateStackTrace(sb, e);
					rp.setData(new BasicDBObject("error_message", "Error deserializing documents: " + sb.toString()), null);
				}
			}
		}		
		catch (Exception e)
		{
			// If an exception occurs log the error
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Test Source",false,"Error testing source: " + e.getMessage()));			
		}
		catch (Error e)
		{
			// If an exception occurs log the error
			logger.error("Exception Message: " + e.getMessage(), e);
			rp.setResponse(new ResponseObject("Test Source",false,"Configuration/Installation error: " + e.getMessage()));						
		}
		return rp;
	}
	
	
	
	//////////////////////////////////////////////////////////////////////////
	//////////////////////// Helper Functions ////////////////////////////////
	//////////////////////////////////////////////////////////////////////////

	/**
	 * isOwnerModeratorOrContentPublisherOrSysAdmin
	 * If the user is a system administrator (DOESN'T HAVE TO BE ENABLED), community owner, or 
	 * moderator they can add a source and isApproved will be true, otherwise 
	 * it will == false and the owner/moderator will need to approve it
	 * @param communityIdStr
	 * @param ownerIdStr
	 * @return
	 */
	private boolean isOwnerModeratorOrContentPublisherOrSysAdmin(String communityIdStr, String ownerIdStr)
	{		
		isOwnerOrModerator = SocialUtils.isOwnerOrModeratorOrContentPublisher(communityIdStr, ownerIdStr);
		if (!isOwnerOrModerator) {
			isSysAdmin = RESTTools.adminLookup(ownerIdStr, false); // (admin doesn't need to be enabled if "admin-on-request")
		}
		boolean isApproved = (isOwnerOrModerator || isSysAdmin) ? true : false;
		return isApproved;
	}	
	/**
	 * isOwnerModeratorOrSysAdmin
	 * If the user is a system administrator (MUST BE ENABLED), community owner, or 
	 * moderator they can add a source and isApproved will be true, otherwise 
	 * it will == false and the owner/moderator will need to approve it
	 * @param communityIdStr
	 * @param ownerIdStr
	 * @return
	 */
	private boolean isOwnerModeratorOrSysAdmin(String communityIdStr, String ownerIdStr)
	{
		isOwnerOrModerator = SocialUtils.isOwnerOrModerator(communityIdStr, ownerIdStr);
		if (!isOwnerOrModerator) {
			isSysAdmin = RESTTools.adminLookup(ownerIdStr);
		}
		boolean isApproved = (isOwnerOrModerator || isSysAdmin) ? true : false;
		return isApproved;
	}	
	private boolean isOwnerOrModerator(String communityIdStr, String ownerIdStr)
	{
		isOwnerOrModerator = SocialUtils.isOwnerOrModerator(communityIdStr, ownerIdStr);
		return isOwnerOrModerator;
	}		
	
	/**
	 * validateSourceKey
	 * Checks source key passed in for uniqueness, if the key is not
	 * unique it adds a number to the end and increments the number
	 * until it is unique
	 * @param id
	 * @param key
	 * @return
	 */
	private String validateSourceKey(ObjectId id, String key)
	{
		///////////////////////////////////////////////////////////////////////
		// Keep appending random numbers to the string until we get a unique
		// match
		
		int counter = 0;
		java.util.Random random = new java.util.Random(new Date().getTime());
		
		StringBuffer sb = new StringBuffer(key).append('.');
		key = sb.toString();
		int nBaseLen = sb.length();
		
		while (!hasUniqueSourceKey(id, key))
		{
			counter++;
			if (0 == (counter % 50)) { // every 50 times another term to the expression
				sb.append('.');
				nBaseLen = sb.length();
			}//(TESTED)
			
			sb.setLength(nBaseLen);
			sb.append(random.nextInt(10000));

			key = sb.toString();
		}
		//(TESTED)
		return sb.toString();
	}
	//TESTED
	
	
	
	
	/**
	 * emailSourceApprovalRequest
	 * @param source
	 * @return
	 */
	private static boolean emailSourceApprovalRequest(SourcePojo source)
	{
		
		
		// Get Information for Person requesting the new source
		PersonPojo p = SocialUtils.getPerson(source.getOwnerId().toString());
		
		// Get the root URL to prepend to the approve/reject link below
		PropertiesManager propManager = new PropertiesManager();
		String rootUrl = propManager.getUrlRoot();
		
		// Subject Line
		String subject = "Approve/Reject New Source: " + source.getTitle();
		
		// Get array of community IDs and get corresponding CommunityPojo objects
		ArrayList<CommunityPojo> communities = SocialUtils.getCommunities(source.getCommunityIds());
		
		// Iterate over the communities and send an email to each set of owner/moderators requesting
		// that the approve or reject the source
		for (CommunityPojo c : communities)
		{
			// Email address or addresses to send to
			// Extract email addresses for owners and moderators from list of community members
			StringBuffer sendTo = new StringBuffer();
			Set<CommunityMemberPojo> members = c.getMembers();
			CommunityMemberPojo owner = null;
			for (CommunityMemberPojo member : members)
			{
				if (member.getUserType().equalsIgnoreCase("owner") || member.getUserType().equalsIgnoreCase("moderator"))
				{
					owner = member;
					if (sendTo.length() > 0) sendTo.append(";");
					sendTo.append(member.getEmail());
				}
			}
			if (0 == sendTo.length()) { 
				throw new RuntimeException("community " + c.getName() + " / " + c.getId() + " has no owner/moderator");
			}
			
			//create a community request and post to db
			CommunityApprovePojo cap = new CommunityApprovePojo();
			cap.set_id(RESTTools.generateRandomId());
			cap.setCommunityId( c.getId().toString() );
			cap.setIssueDate(new Date());
			cap.setPersonId(owner.get_id().toString());
			cap.setRequesterId(p.get_id().toString());
			cap.setType("source");
			cap.setSourceId(source.getId().toString());
			DbManager.getSocial().getCommunityApprove().insert(cap.toDb());		
			
			// Message Body
			String body = "<p>" + p.getDisplayName() + " has requested that the following source be " +
				"added to the " + c.getName() + " community:</p>" + 
				"<p>" +
				"Title: " + source.getTitle() + "<br/>" + 
				"Description: " + source.getDescription() + "<br/>" + 
				"URL (eg): " + source.getRepresentativeUrl() + "<br/>" + 
				"</p>" +
				"<p>Please click on the Approve or Reject links below to complete the approval process: </p>" +
				"<li><a href=\"" + rootUrl + "social/community/requestresponse/" + cap.get_id().toString() + "/true\">Approve new Source</a></li>" +
				"<li><a href=\"" + rootUrl + "social/community/requestresponse/" + cap.get_id().toString() + "/false\">Reject new Source</a></li>";							
			
			// Send
			new SendMail(new PropertiesManager().getAdminEmailAddress(), sendTo.toString(), subject, body).send("text/html");	
		}
		return true;
	}
	
	
	/**
	 * emailSourceApproval
	 * @param source
	 * @return
	 */
	private static boolean emailSourceApproval(SourcePojo source, String approverIdStr, String decision)
	{
		// Get Information for Person requesting the new source
		PersonPojo submitter = SocialUtils.getPerson(source.getOwnerId().toString());
		
		// Get Information for Person making approval decision
		PersonPojo approver = SocialUtils.getPerson(approverIdStr);
	
		// Subject Line
		String subject = "Request to add new Source " + source.getTitle() + " was " + decision;

		// Message Body
		String body = "<p>Your request to add the following source:</p>" + 
		"<p>" +
		"Title: " + source.getTitle() + "<br/>" + 
		"Description: " + source.getDescription() + "<br/>" + 
		"URL: " + source.getRepresentativeUrl() + "<br/>" + 
		"</p>" +
		"<p>Was <b>" + decision + "</b> by " + approver.getDisplayName() + "</p>";

		// Send
		new SendMail(new PropertiesManager().getAdminEmailAddress(), submitter.getEmail(), subject, body).send("text/html");	
		return true;
	}
	
	/**
	 * hasRequiredSourceFields
	 * @param s
	 * @return
	 */
	private String hasRequiredSourceFields(SourcePojo s)
	{
		ArrayList<String> fields = new ArrayList<String>();
		String url = s.getRepresentativeUrl();
		if (null == url) {
			fields.add("URL");				
		}
		if (s.getTitle() == null) fields.add("Title");
		if (s.getMediaType() == null) fields.add("Media Type");
		if ((s.getExtractType() == null) && (s.getProcessingPipeline() == null)) fields.add("Extract Type");
		
		if (fields.size() > 0)
		{
			StringBuffer sb = new StringBuffer();
			sb.append("Unable to add source. The following required field/s are missing: ");
			int count = 0;
			for (String field : fields)
			{
				sb.append(field);
				if (count < (fields.size() - 1)) sb.append(", ");
				count++;
			}
			sb.append(".");
			
			return sb.toString();		
		}
		return null;
	}

	
	
	/**
	 * hasUniqueSourceKey
	 * Checks to ensure that a sourcekey is unique across all sources in the
	 * harvester.sources collection
	 * @param key
	 * @return
	 */
	public static boolean hasUniqueSourceKey(ObjectId sourceId, String key)
	{
		boolean isUnique = true;
		BasicDBObject query = new BasicDBObject();
		query.put(SourcePojo._id_, new BasicDBObject(MongoDbManager.ne_,sourceId));
		query.put(SourcePojo.key_, key);
		try
		{
			DBObject dbo = DbManager.getIngest().getSource().findOne(query);
			if (dbo != null)
			{
				isUnique = false;
			}
		}
		catch (Exception e)
		{
			logger.error("Exception Message: " + e.getMessage(), e);
		}
		return isUnique;
	}
	
	
	/**
	 * isUniqueSource
	 * Determine whether or not a source is unique based on its ID and Shah-256 Hash - and if it has the same set of URLs...
	 * Not perfect since will have (rare) false positives and also doesn't handle the multiple URLs very well
	 * @param sourceid
	 * @param shah256Hash
	 * @return
	 */
	private static boolean isUniqueSource(SourcePojo source, Collection<ObjectId> communityIdList)
	{
		try
		{
			BasicDBObject query = new BasicDBObject();
			query.put(SourcePojo._id_, new BasicDBObject(MongoDbManager.ne_, source.getId()));
			query.put(SourcePojo.communityIds_, new BasicDBObject(MongoDbManager.in_,communityIdList));
			query.put(SourcePojo.shah256Hash_, source.getShah256Hash());
			List<SourcePojo> sourceList = SourcePojo.listFromDb(DbManager.getIngest().getSource().find(query), SourcePojo.listType());

			// We'll just check the first URL here
			String myRepUrl = source.getRepresentativeUrl();			
			if (null != sourceList) {
				for (SourcePojo otherSource: sourceList) {
					String theirRepUrl = otherSource.getRepresentativeUrl();
					
					if (myRepUrl.equalsIgnoreCase(theirRepUrl)) {
						return false;
					}
				}
			}//TESTED
			
		}
		catch (Exception e) {}
		return true;
	}//TESTED
	
	
	/**
	 * getSource
	 * @param sourceIdStr
	 * @return
	 */
	private static SourcePojo getSource(String sourceIdStr)
	{
		SourcePojo source = null;
		try
		{
			BasicDBObject query = new BasicDBObject();
			query.put(SourcePojo._id_, new ObjectId(sourceIdStr));
			source = SourcePojo.fromDb(DbManager.getIngest().getSource().findOne(query), SourcePojo.class);
		}
		catch (Exception e)
		{

		}
		return source;
	}
	
	// Utility: make life easier in terms of adding/update/inviting/leaving from the command line
	
	private static String allowCommunityRegex(String userIdStr, String communityIdStr) {
		if (communityIdStr.startsWith("*")) {
			String[] communityIdStrs = SocialUtils.getCommunityIds(userIdStr, communityIdStr);	
			if (1 == communityIdStrs.length) {
				communityIdStr = communityIdStrs[0]; 
			}
			else {
				throw new RuntimeException("Invalid community pattern, matched " + communityIdStrs.length + " communities: " + ArrayUtils.toString(communityIdStrs));
			}
		}	
		return communityIdStr;
	}
	
	/**
	 * Turns a source off/on according to shouldSuspend
	 * 
	 * @param sourceid
	 * @param communityid
	 * @param cookieLookup
	 * @param shouldSuspend
	 * @return
	 */
	public ResponsePojo suspendSource(String sourceIdStr, String communityIdStr, String personIdStr, boolean shouldSuspend) 
	{
		ResponsePojo rp = new ResponsePojo();
		boolean isApproved = isOwnerModeratorOrContentPublisherOrSysAdmin(communityIdStr, personIdStr);
		if ( isApproved )
		{
			//get source
			BasicDBObject query = new BasicDBObject();
			query.put(SourcePojo._id_, new ObjectId(sourceIdStr));
			SourcePojo source = SourcePojo.fromDb(DbManager.getIngest().getSource().findOne(query), SourcePojo.class);
			if ( source != null )
			{
				//set the search cycle secs in order of user -> toplevel -> nonexist
				//if turning off: set to negative of user - toplevel or -1
				//if turning on: set to positive of user - toplevel or null
				BasicDBObject update = new BasicDBObject(SourcePojo.modified_, new Date());				
				int searchCycle_secs_sgn = getSearchCycleSecs(source);
				int searchCycle_secs = Math.abs( searchCycle_secs_sgn );
				if ( shouldSuspend )
				{
					//turn off the source
					if ( searchCycle_secs > 0 )
					{
						update.put(SourcePojo.searchCycle_secs_, -searchCycle_secs);
					}
					else
					{
						update.put(SourcePojo.searchCycle_secs_, -1);						
					}
				}
				else
				{
					//SPECIAL CASE: DON'T ALLOW THIS FOR LOGSTASH SOURCES BECAUSE NEED TO VALIDATE BEFORE RE-ACTIVATING
					if (source.getExtractType().equalsIgnoreCase("logstash")) {
						rp.setResponse(new ResponseObject("suspendSource", false, "Can't un-suspend logstash sources using this API call, unsuspend manually (eg from the Source Editor form)"));
						return rp;
					}//TESTED
					
					//SPECIAL CASE: enforce a minimum search cycle of distributed types
					else if (InfiniteEnums.POSTPROC == InfiniteEnums.castExtractType(source.getExtractType())) {
						rp.setResponse(new ResponseObject("suspendSource", false, "Can't un-suspend post processing sources using this API call, unsuspend manually (eg from the Source Editor form)"));
						return rp;
					}//TESTED (by hand)							
					
					//turn on the source
					if ( searchCycle_secs_sgn >= 0 )
					{
						update.put(SourcePojo.searchCycle_secs_, searchCycle_secs);						
					}
					else
					{
						update.put(SourcePojo.searchCycle_secs_, null);
					}
					// If has harvest bad source then remove that also
					update.put(SourcePojo.harvestBadSource_, false);
				}
				
				//save the source
				DbManager.getIngest().getSource().update(query, new BasicDBObject(MongoDbManager.set_, update));
				rp.setResponse(new ResponseObject("suspendSource", true, "Source suspended/unsuspended successfully"));
			}
			else
			{
				rp.setResponse(new ResponseObject("suspendSource", false, "No source with this id found"));
			}
		}
		else
		{
			rp.setResponse(new ResponseObject("suspendSource", false, "must be owner or admin to suspend this source"));
		}
		return rp;
	}//TODO (INF-2549) TOTEST (community owner/mod, source owner)

	/**
	 * Returns the searchCycle_secs for a source.  Will attempt to grab a users source
	 * pipeline search cycle, if that doesn't exist, will return the top level searchCycle.
	 * If that doesn't exist will return 0.
	 * 
	 * @param source
	 * @return
	 */
	private int getSearchCycleSecs(SourcePojo source) 
	{
		//try to get user pipeline searchCycle
		if (null != source.getProcessingPipeline()) 
		{
			for (SourcePipelinePojo px : source.getProcessingPipeline()) 
			{				
				if (null != px && null != px.harvest && null != px.harvest.searchCycle_secs ) 
				{
					return px.harvest.searchCycle_secs;
				}
			}
		}
				
		//if that doesn't exist, get regular searchCycle
		if ( null != source.getSearchCycle_secs() )
			return source.getSearchCycle_secs();
		
		//if that doesn't exist, return 0
		return 0;
	}		

	//TODO (INF-2533): Logstash: Handle delete source, delete source docs
}

