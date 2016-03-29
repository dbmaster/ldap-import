import com.branegy.inventory.api.InventoryService
import com.branegy.service.connection.api.ConnectionService
import com.branegy.dbmaster.connection.ConnectionProvider
import com.branegy.service.core.exception.EntityNotFoundApiException
import com.branegy.dbmaster.util.NameMap
import com.branegy.service.core.QueryRequest
import com.branegy.inventory.model.Application
import com.branegy.inventory.model.Server
import com.branegy.inventory.model.Installation
import java.util.Date
import com.branegy.inventory.api.ContactService
import com.branegy.inventory.model.*

def convertMapping = { mappingText ->
    def m = [:]
    if (mappingText!=null && !mappingText.isEmpty()) {
        for (String row : mappingText.split("(\r\n|\n)")) {
            if (row.contains('=') && !row.trim().isEmpty()) {
                String[] kv = row.split("=",2);
                logger.debug("m=${m}  ${kv[0]} ${kv[1]} " )
                m[kv[0]] = kv[1].isEmpty() ? null : kv[1];
            }
        }
    }
    return m
}

connectionSrv = dbm.getService(ConnectionService.class)
def ldapQuery = new LdapQuery ( dbm, logger)

def mapping = convertMapping(p_attributes);


def attributes = mapping.values().collect() 
attributes.add("givenName")
attributes.add("sAMAccountName")
attributes.add("sn")

//["sAMAccountName" ,"distinguishedName" , "name" , "userAccountControl"]
def ldapObjects = ldapQuery.loadLdapAccounts(connectionSrv, p_base_context, p_ldap_query, attributes)

def contactService = dbm.getService(ContactService.class)
def inventoryContacts = contactService.getContactList(new QueryRequest())

Contact contact;

ldapObjects.each { ldapContact->
    logger.info("mail = ${ldapContact.mail} all ${ldapContact}")
    if (ldapContact.mail!=null) {
        contact = inventoryContacts.find { it.getCustomData("ContactEmail") == ldapContact.mail }
        if (contact==null) {
            contact = new Contact()
            contact.setContactName(ldapContact.givenName+" "+ldapContact.sn)
            logger.debug("Creating a new contact")
        }
        mapping.each { k, v ->
            contact.setCustomData(k, ldapContact[v])
            logger.debug("Setting ${k} to  ${ldapContact[v]} attr: ${v}")
        }
        logger.debug("Contact id = ${contact.id}")
        if (contact.id==0) {
            contactService.createContact(contact)
        }
    }    
}



// contactService.createContact(target)



/*
connectionInfo = connectionSrv.findByName(p_server)
connector = ConnectionProvider.getConnector(connectionInfo)

def context = connector.connect().getContext()
dbm.closeResourceOnExit(context)

SearchControls ctrl = new SearchControls();
// a candidate for script parameter
ctrl.setSearchScope(SearchControls.SUBTREE_SCOPE);
// a candidate for script parameter
ctrl.setCountLimit(100000);
// a candidate for script parameter
ctrl.setTimeLimit(10000); // 10 second == 10000 ms

p_attributes = "extensionAttribute8;description;distinguishedName;sAMAccountName;objectCategory"

def categories = [
   "CN=Group,CN=Schema,CN=Configuration,DC=MOVADOGROUP,DC=com":"User Group",
   "CN=Person,CN=Schema,CN=Configuration,DC=MOVADOGROUP,DC=com":"User",
   "CN=Computer,CN=Schema,CN=Configuration,DC=MOVADOGROUP,DC=com":"Computer"
]

if (p_attributes!=null && p_attributes.length()>0) {
    def attrIDs = p_attributes.split(";")
    ctrl.setReturningAttributes(attrIDs);
}
println "<pre>"

applications = new NameMap()

    NamingEnumeration enumeration = context.search(p_base, p_query, ctrl);
    try {
        while (enumeration.hasMore()) {
            SearchResult result = (SearchResult) enumeration.next();
            Attributes attribs = result.getAttributes();
            def attrID = attribs.getIDs();

            def ldapObject = new TreeMap()

            while (attrID.hasMore()) {
                def attribute = attrID.next()
                def value = ((BasicAttribute) attribs.get(attribute))

                def valueStr = ""
                if (value!=null) {
                    NamingEnumeration values = value.getAll();
                    while (values.hasMore()) {
                        def nextValue = values.next().toString()
                        if (valueStr.length()>0) { valueStr = valueStr +";" }
                        valueStr = valueStr + nextValue
                        if (attribute=="extensionAttribute8") {
                            if (applications[valueStr]==null) {
                                applications[valueStr] = []
                            }
                            applications[valueStr].add(ldapObject)
                        }
                    }
                    ldapObject[attribute] = valueStr
                }
            }
            // println ldapObject.toString()
        }
    } catch (javax.naming.PartialResultException e) {
        println "PartialResultException"
    }  catch (SizeLimitExceededException e) {
        // for paging see
        // http://www.forumeasy.com/forums/thread.jsp?tid=115756126876&fid=ldapprof2&highlight=LDAP+Search+Paged+Results+Control
    }

    println "Updating Applications..."

    def inventoryService = dbm.getService(InventoryService.class);
    
    def allApplicationMap = new NameMap();
    for (Application ap:inventoryService.getApplicationList(new QueryRequest())){
        allApplicationMap.put(ap.getApplicationName(), ap);
    }
    
    def allServerMap = new NameMap();
    for (Server serv:inventoryService.getServerList(new QueryRequest())){
        allServerMap.put(serv.getServerName(), serv);
    }
    
    applications.each { applicationName, ldapObjects ->
        println applicationName
        logger.info("Searching for application ${applicationName}")
        def application = allApplicationMap.remove(applicationName);
        if (application == null){
            def msg = """Application ${applicationName} not found as defined in
                     <ul>${ ldapObjects.collect{"<li>"+it["distinguishedName"]+"</li>"}.join() }</ul>"""
            println """<div class="bc-error">Error:${msg}</div>"""
            logger.error(msg)
        } else {
            // def separator = "--------------------------------"
            value = """<table class="simple-table" cellspacing="0">
                           <tr><td>Object Type</td><td>Name</td><td>Description</td></tr>
                           ${ldapObjects.sort{ (categories[it["objectCategory"]] ?: it["objectCategory"])+""+it["sAMAccountName"] }.collect{entry -> """<tr>
                                <td>${categories[entry["objectCategory"]] ?: entry["objectCategory"]}</td>
                        <td>${entry["sAMAccountName"]}</td>
                        <td>${entry["description"] ?: ""}</td>
    
            </tr>"""}.join() }
                       </table>
                    """
              println value
    
            application.setCustomData("LDAPObjects",value.toString())
            inventoryService.updateApplication(application);
            
            // update installation
            def installationMap = new NameMap();
            for (Installation inst:inventoryService.findInstallationByApplication(application.getId())){
                installationMap.put(inst.getServer().getServerName(), inst);
            }
            
            ldapObjects
                .findAll{ it -> "Computer".equals(categories[it["objectCategory"]])}
                .each{ map ->
                    String serverName = map["sAMAccountName"].replaceAll(/\$$/,"");
                    Installation inst = installationMap.get(serverName);
                    if (inst != null){
                        inst.setLastSynch(new Date());
                        inst.setCustomData("Source", "ActiveDirectory")
                        inventoryService.saveApplicationInstance(inst);
                        //logger.debug("update installation {}::{}", applicationName,serverName);
                    } else {
                        Server s = allServerMap.get(serverName);
                        if (s!=null){
                            inst = new Installation();
                            inst.setApplication(application);
                            inst.setServer(s);
                            inst.setLastSynch(new Date());
                            inst.setCustomData("Source","ActiveDirectory");
                            inventoryService.saveApplicationInstance(inst);
                            //logger.debug("create installation {}::{}", applicationName,serverName);
                        }
                    }
                }
        }
    }
    allApplicationMap.values().each { application ->
        if (application.getCustomData("LDAPObjects")!=null) {
            application.setCustomData("LDAPObjects",null)
            inventoryService.updateApplication(application)
            logger.info("No objects for application ${application.getApplicationName()}")
        }
    }
    
    println "Done..."

    println "</pre>"
  */