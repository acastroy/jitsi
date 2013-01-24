/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.ldap;

import java.util.*;
import java.util.regex.*;

import net.java.sip.communicator.plugin.desktoputil.*;
import net.java.sip.communicator.service.contactsource.*;
import net.java.sip.communicator.service.ldap.*;
import net.java.sip.communicator.service.ldap.event.*;
import net.java.sip.communicator.service.protocol.*;

/**
 * Implements <tt>ContactQuery</tt> for LDAP.
 * <p>
 * In contrast to other contact source implementations like AddressBook and
 * Outlook the LDAP contact source implementation is explicitly moved to the
 * "impl.ldap" package in order to allow us to create LDAP contact sources
 * for ldap directories through the LdapService.
 * </p>
 *
 * @author Sebastien Vincent
 * @author Yana Stamcheva
 */
public class LdapContactQuery
    extends AsyncContactQuery<LdapContactSourceService>
{
    /**
     * Maximum results for LDAP query.
     */
    public static final int LDAP_MAX_RESULTS = 40;

    /**
     * Maximum number of results for this instance.
     */
    private final int count;

    /**
     * LDAP query.
     */
    private LdapQuery ldapQuery = null;

    /**
     * Object lock.
     */
    private final Object objLock = new Object();

    /**
     * Initializes a new <tt>LdapContactQuery</tt> instance which is to perform
     * a specific <tt>query</tt> on behalf of a specific <tt>contactSource</tt>.
     *
     * @param contactSource the <tt>ContactSourceService</tt> which is to
     * perform the new <tt>ContactQuery</tt> instance
     * @param query the <tt>Pattern</tt> for which <tt>contactSource</tt> is
     * being queried
     * @param count maximum number of results
     */
    protected LdapContactQuery(LdapContactSourceService
            contactSource, Pattern query, int count)
    {
        super(contactSource, query);
        this.count = count;
    }

    /**
     * Performs this <tt>AsyncContactQuery</tt> in a background <tt>Thread</tt>.
     *
     * @see AsyncContactQuery#run()
     */
    @Override
    protected void run()
    {
        /* query we get is delimited by \Q and \E
         * and we should not query LDAP server with a too small number of
         * characters
         */
        String queryStr = query.toString();
        if(queryStr.length() < (4))
        {
            return;
        }

        /* remove \Q and \E from the Pattern */
        String queryString = queryStr.substring(2, queryStr.length() - 2);
        LdapService ldapService = LdapActivator.getLdapService();
        LdapFactory factory = ldapService.getFactory();

        ldapQuery = factory.createQuery(queryString);
        LdapSearchSettings settings = factory.createSearchSettings();
        settings.setDelay(250);
        settings.setMaxResults(count);

        LdapListener caller = new LdapListener()
        {
            public void ldapEventReceived(LdapEvent evt)
            {
                processLdapResponse(evt);
            }
        };

        LdapDirectory ldapDir = getContactSource().getLdapDirectory();
        if(ldapDir == null)
        {
            return;
        }

        ldapDir.searchPerson(ldapQuery, caller, settings);

        synchronized(objLock)
        {
            try
            {
                objLock.wait();
            }
            catch(InterruptedException e)
            {
            }
        }
    }

    /**
     * Gets the <tt>contactDetails</tt> to be set on a <tt>SourceContact</tt>.
     *
     * @param person LDAP person
     * @return the <tt>contactDetails</tt> to be set on a <tt>SourceContact</tt>
     */
    private List<ContactDetail> getContactDetails(LdapPersonFound person)
    {
        List<ContactDetail> ret = new LinkedList<ContactDetail>();
        Set<String> mailAddresses = person.getMail();
        Set<String> mobilePhones = person.getMobilePhone();
        Set<String> homePhones = person.getHomePhone();
        Set<String> workPhones = person.getWorkPhone();
        ContactDetail detail = null;

        for(String mail : mailAddresses)
        {
            List<Class<? extends OperationSet>> supportedOpSets
                            = new ArrayList<Class<? extends OperationSet>>(1);
            // can be added as contacts
            supportedOpSets.add(OperationSetPersistentPresence.class);

            detail = new ContactDetail(mail, ContactDetail.CATEGORY_EMAIL,
                    null);
            detail.setSupportedOpSets(supportedOpSets);
            ret.add(detail);
        }

        for(String homePhone : homePhones)
        {
            List<Class<? extends OperationSet>> supportedOpSets
                = new ArrayList<Class<? extends OperationSet>>(2);

            supportedOpSets.add(OperationSetBasicTelephony.class);
            // can be added as contacts
            supportedOpSets.add(OperationSetPersistentPresence.class);
            homePhone = PhoneNumberI18nService.normalize(homePhone);
            detail = new ContactDetail(homePhone,
                    ContactDetail.CATEGORY_PHONE,
                    new String[]{ContactDetail.LABEL_HOME});
            detail.setSupportedOpSets(supportedOpSets);
            ret.add(detail);
        }

        for(String workPhone : workPhones)
        {
            List<Class<? extends OperationSet>> supportedOpSets
                = new ArrayList<Class<? extends OperationSet>>(2);

            supportedOpSets.add(OperationSetBasicTelephony.class);
            // can be added as contacts
            supportedOpSets.add(OperationSetPersistentPresence.class);
            workPhone = PhoneNumberI18nService.normalize(workPhone);
            detail = new ContactDetail(workPhone,
                    ContactDetail.CATEGORY_PHONE,
                    new String[]{ContactDetail.LABEL_WORK});
            detail.setSupportedOpSets(supportedOpSets);
            ret.add(detail);
        }

        for(String mobilePhone : mobilePhones)
        {
            List<Class<? extends OperationSet>> supportedOpSets
                = new ArrayList<Class<? extends OperationSet>>(2);

            supportedOpSets.add(OperationSetBasicTelephony.class);
            // can be added as contacts
            supportedOpSets.add(OperationSetPersistentPresence.class);
            mobilePhone = PhoneNumberI18nService.normalize(mobilePhone);
            detail = new ContactDetail(mobilePhone,
                    ContactDetail.CATEGORY_PHONE,
                    new String[]{ContactDetail.LABEL_MOBILE});
            detail.setSupportedOpSets(supportedOpSets);
            ret.add(detail);
        }

        return ret;
    }

    /**
     * Process LDAP event.
     *
     * @param evt LDAP event
     */
    private void processLdapResponse(LdapEvent evt)
    {
        if(evt.getCause() == LdapEvent.LdapEventCause.SEARCH_ACHIEVED ||
                evt.getCause() == LdapEvent.LdapEventCause.SEARCH_CANCELLED)
        {
            synchronized(objLock)
            {
                objLock.notify();
            }
        }

        if(evt.getCause() == LdapEvent.LdapEventCause.NEW_SEARCH_RESULT)
        {
            LdapPersonFound person = (LdapPersonFound) evt.getContent();
            String displayName = null;

            if(person == null)
            {
                return;
            }

            if(person.getDisplayName() != null)
            {
                displayName = person.getDisplayName();
            }
            else
            {
                displayName = person.getFirstName() + " " + person.getSurname();
            }

            List<ContactDetail> contactDetails = getContactDetails(person);

            if (!contactDetails.isEmpty())
            {
                GenericSourceContact sourceContact
                    = new GenericSourceContact(
                            getContactSource(),
                            displayName,
                            contactDetails);

                try
                {
                    sourceContact.setImage(person.fetchPhoto());
                }
                catch (OutOfMemoryError oome)
                {
                    // Ignore it, the image is not vital.
                }

                addQueryResult(sourceContact);
            }
        }
        else if(evt.getCause() == LdapEvent.LdapEventCause.SEARCH_AUTH_ERROR)
        {
            synchronized(objLock)
            {
                objLock.notify();
            }

            /* show authentication window to obtain new credentials */
            new Thread()
            {
                public void run()
                {
                    LdapDirectorySettingsImpl ldapSettings =
                        (LdapDirectorySettingsImpl) getContactSource()
                            .getLdapDirectory().getSettings();

                    AuthenticationWindow authWindow
                        = new AuthenticationWindow(ldapSettings.getUserName(),
                            ldapSettings.getPassword().toCharArray(),
                            ldapSettings.getName(),
                            false,
                            LdapActivator.getResourceService().getImage(
                                "service.gui.icons.AUTHORIZATION_ICON"),
                            LdapActivator.getResourceService().getI18NString(
                                "impl.ldap.WRONG_CREDENTIALS",
                                new String[]{ldapSettings.getName()}));

                    authWindow.setVisible(true);

                    if(!authWindow.isCanceled())
                    {
                        LdapDirectorySettings newSettings
                            = new LdapDirectorySettingsImpl(ldapSettings);

                        // Remove old server.
                        LdapService ldapService
                            = LdapActivator.getLdapService();

                        LdapFactory factory = ldapService.getFactory();
                        LdapDirectory ldapDir
                            = getContactSource().getLdapDirectory();
                        LdapActivator.unregisterContactSource(ldapDir);
                        ldapService.getServerSet().removeServerWithName(
                                ldapSettings.getName());

                        // Add new server.
                        newSettings.setPassword(
                            new String(authWindow.getPassword()));

                        ldapDir = factory.createServer(newSettings);
                        ldapService.getServerSet().addServer(ldapDir);

                        LdapActivator.registerContactSource(ldapDir);
                    }
                }
            }.start();
        }
    }

    /**
     * Notifies this <tt>LdapContactQuery</tt> that it has stopped performing
     * in the associated background <tt>Thread</tt>.
     *
     * @param completed <tt>true</tt> if this <tt>ContactQuery</tt> has
     * successfully completed, <tt>false</tt> if an error has been encountered
     * during its execution
     * @see AsyncContactQuery#stopped(boolean)
     */
    @Override
    protected void stopped(boolean completed)
    {
        try
        {
            super.stopped(completed);
        }
        finally
        {
            getContactSource().stopped(this);
        }
    }

    /**
     * Cancels this <tt>ContactQuery</tt>.
     *
     * @see ContactQuery#cancel()
     */
    @Override
    public void cancel()
    {
        if(ldapQuery != null)
        {
            ldapQuery.setState(LdapQuery.State.CANCELLED);
        }

        synchronized(objLock)
        {
            objLock.notify();
        }
        super.cancel();
    }
}
