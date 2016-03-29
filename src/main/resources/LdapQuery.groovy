// package io.dbmaster.tools

import com.branegy.scripting.DbMaster
import com.branegy.dbmaster.util.NameMap
import org.slf4j.Logger
import io.dbmaster.tools.LdapSearch

import javax.naming.*
import javax.naming.directory.*
import javax.naming.ldap.*


public class LdapQuery { 
    
    private DbMaster dbm
    private Logger logger

    public LdapQuery(DbMaster dbm, Logger logger) {
        this.dbm = dbm
        this.logger = logger
    }
   
    public List loadLdapAccounts(connectionSrv, String baseContext, String query, Collection<String> importAttributes) {
        def ldapConns = connectionSrv.getConnectionList().findAll { it.driver=="ldap" }
        def ldapObjects = []
        ldapConns.each { connection->
            logger.info("Loading users and group from  ${connection.name}")
            def ldapSearch = new LdapSearch(dbm, logger)    
            def ldapQuery  = query;// "(|(objectClass=user)(objectClass=group))"
            
            def ldapAttributes = importAttributes.join(";");
            logger.info("Retrieving ldap accounts and groups")
            
            String ldapContext = null
            String domain = null
            connection.properties.each { p->
                if (p.key == "defaultContext") {
                    ldapContext = p.value
                    logger.info("Found context = ${ldapContext}")
                }
                if (p.key == "domain") { 
                     domain = p.value
                     logger.info("Domain = ${domain}")
                }
            }
            if (baseContext!=null) {
                ldapContext = baseContext;
            }

            if (ldapContext==null) {
                logger.warn("Define property 'defaultContext' for connection '${connection.name}'")
            }
            if (domain==null) {
                logger.warn("Define property 'domain' for connection '${connection.name}'")
            }
            
            if (ldapContext!=null && domain!=null) {
                def search_results = ldapSearch.search(connection.name, ldapContext, ldapQuery, ldapAttributes)
                
                def getAll = { ldapAttribute ->
                    def result = null
                    if (ldapAttribute!=null) {
                        result = []
                        def values = ldapAttribute.getAll()
                        while (values.hasMore()) {
                            result.add(values.next().toString())
                        }
                    }
                    return result
                }
                
                logger.info("Found ${search_results.size()} records")
                
                search_results.each { result_item ->  
                    Attributes attributes = result_item.getAttributes()
                    // def name = attributes.get("sAMAccountName")?.get()
                    // def dn = attributes.get("distinguishedName")?.get()
                    // def members = getAll(attributes.get("member"))
                    // def member_of = getAll(attributes.get("memberOf"))
                    // def userAccountControl = attributes.get("userAccountControl")?.get()
                    def ldapObject = [:]

                    importAttributes.each { attrName ->
                        def attr  = attributes.get(attrName)
                        def value = attr?.get()
                        ldapObject[attrName] = value
                    }
                    ldapObjects.add (ldapObject)
                }
            }
        }   
        return ldapObjects
    }
}