/**Copyright 2016, University of Messina.
*
*   Licensed under the Apache License, Version 2.0 (the "License");
*   you may not use this file except in compliance with the License.
*   You may obtain a copy of the License at
*
*       http://www.apache.org/licenses/LICENSE-2.0
*
*   Unless required by applicable law or agreed to in writing, software
*   distributed under the License is distributed on an "AS IS" BASIS,
*   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*   See the License for the specific language governing permissions and
*   limitations under the License.
*/
package OSFFM_ORC;

//<editor-fold defaultstate="collapsed" desc="Import Section">
import JClouds_Adapter.Heat;
import JClouds_Adapter.NeutronTest;
import JClouds_Adapter.NovaTest;
import MDBInt.DBMongo;
import OSFFM_ORC.Utils.Exception.NotFoundGeoRefException;
import OSFFM_ORC.Utils.MultiPolygon;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.jclouds.openstack.neutron.v2.domain.Port;
import org.json.JSONException;
import org.json.JSONObject;

import org.openstack4j.model.heat.Resource;
import org.openstack4j.model.heat.Stack;

import org.yaml.snakeyaml.Yaml;
import JClouds_Adapter.OpenstackInfoContainer;
//</editor-fold>

/**
 *
 * @author Giuseppe Tricomi
 */
public class OrchestrationManager {
    static HashMap<String,ManifestManager> mapManifestThr=new HashMap<String,ManifestManager>();//mappa che mantiene riferimenti manifest- manifest manager
    HashMap<String,ArrayList> globalTOfragmentsManif;//BEACON>>> this variable need to be used in splitting alghoritm
    
    public OrchestrationManager() {
        this.globalTOfragmentsManif=new HashMap<String,ArrayList>();
    }
    /**
     * Function called from API when web service is invocated.
     * @param Manifest
     */
    public void addManifestToWorkf(String nameMan,JSONObject manifest){
        ManifestManager mm=new ManifestManager(nameMan,manifest);
        mm.run();
    }
    
    /**
     * Function used to insert hte ManifestManager element inside the static Table.
     * @param name
     * @param thread 
     */
    public static void putEntryinTable(String name,ManifestManager thread){
        OrchestrationManager.mapManifestThr.put(name, thread);
    }
    
    /**
     * Function used to write Yaml manifest inside a specific file passed in resource nameMan.
     * @param nameMan
     * @param jobj 
     */
    private void writeManifestonFile(String nameMan,String jobj){
        ManifestManager mm=OrchestrationManager.mapManifestThr.get(nameMan);
        FileWriter w;
        try{
            w=new FileWriter(nameMan);//scegliere oppoortunamente il nome del file per il salvataggio
            BufferedWriter b=new BufferedWriter (w);
            b.write(jobj);
            b.flush();
        }
        catch(Exception e){}
    }
    
    /**
     * Function used to manage the YAML files creation, one for each Service Group found inside original Manifest.
     * The naming procedure used to name the YAML files resulting is: rootName+_+"OS::Beacon::ServiceGroupManagement resource name".
     * @param mm
     * @param rootName 
     */
    private void manageYAMLcreation(ManifestManager mm,String rootName,String tenant){
        Set ks=mm.table_resourceset.get("OS::Beacon::ServiceGroupManagement").keySet();
        Object[] obj=ks.toArray();
        for(int index=0;index<ks.size();index++){
            String val="";
            try{
                val=mm.ComposeJSON4element((String)obj[index]);
            }
            catch(JSONException je){
                System.err.println("A JSONException is occurred"+je.getMessage());
            }
            catch(Exception e){
                System.err.println("A generic Exception is occurred"+e.getMessage());
            }
            this.writeManifestonFile("./subrepoTemplate/"+tenant+rootName+"_"+(String)obj[index], val);
            //ArrayList<String> tmp=this.globalTOfragmentsManif.get(rootName);
            //tmp.add(rootName+"_"+(String)obj[index]);
            //this.globalTOfragmentsManif.put(rootName,tmp);
        }
    }
    
    /**
     * 
     * @param manName uuid manifest passed from dashboard
     */
    public void manifestinstatiation(String manName,String tenant){
        //retrieve Manifest from MongoDB, it is JSONObject.
        JSONObject manifest=null;
        //verifica della versione del manifest rimandata al futuro, per adesso lo rielaboro
        this.addManifestToWorkf(manName, manifest);
        ManifestManager mm=(ManifestManager)OrchestrationManager.mapManifestThr.get(manName);
        this.manageYAMLcreation(mm, manName,tenant);
        //lancio su heat i comandi per l'istanziazione degli stack.
    }
    
    /**
     * 
     * @param manName
     * @param manifest
     * @param tenant 
     */
    public void manifestinstatiation(String manName,JSONObject manifest,String tenant){
        this.addManifestToWorkf(manName, manifest);
        ManifestManager mm=(ManifestManager)OrchestrationManager.mapManifestThr.get(manName);
        this.manageYAMLcreation(mm, manName,tenant);
        //lancio su heat i comandi per l'istanziazione degli stack.
    }
    
    /**
     * This function split the global federation Manifest in single Manifest for each stack 
     * ready to be deployed in federated cloud.
     * @param manName
     * @param tenant 
     */
    public void splitManifest(String manName,String tenant){
        ManifestManager mm=(ManifestManager)OrchestrationManager.mapManifestThr.get(manName);
        this.manageYAMLcreation(mm, manName, tenant);
    }   
    
    /**
     * It returns an HashMap that correlate ServiceGroup name with the ArrayList that contains 
     * the ArrayList of String representation of JSONObject of the Datacenter info.
     * These Datacenter set (represented by the inner ArrayList) is the dc, existent inside the shape
     * descript in geoshape; the external ArrayList is the set of all previous entity retrieved for each shape.
     * The external ArrayList is a priority ordered List.
     * @param manName
     * @param db
     * @param tenant
     * @return 
     */
    public HashMap<String,ArrayList<ArrayList<String>>> managementgeoPolygon(String manName,MDBInt.DBMongo db,String tenant){
        HashMap<String,ArrayList<ArrayList<String>>> tmp=new HashMap<String,ArrayList<ArrayList<String>>>();//mappa contenente associazione nome shape con Datacenter ID&info
//salvare questa mappa come oggetto dell'orchestrator
        ManifestManager mm=(ManifestManager)OrchestrationManager.mapManifestThr.get(manName);
        Set s=mm.table_resourceset.get("OS::Beacon::ServiceGroupManagement").keySet();
        Iterator it=s.iterator();
        while(it.hasNext()){
            String serName=(String)it.next();
            SerGrManager sgm=(SerGrManager)mm.serGr_table.get(serName);
            ArrayList<MultiPolygon> ar=null;
            try{
                ar=(ArrayList<MultiPolygon>)mm.geo_man.retrievegeoref(sgm.getGeoreference());
            }catch(NotFoundGeoRefException ngrf){
                System.err.println("An error is occourred in retrievegeoref. The GeoManager doesn't contain the shape searched.\n"+ngrf.getMessage());
                ngrf.printStackTrace();
            }
            ArrayList dcInfoes=new ArrayList();
            for(int index=0;index<ar.size();index++){
                try{
                    ArrayList<String> dcInfo=db.getDatacenters(tenant,ar.get(index).toJSONString());
                    dcInfoes.add(dcInfo);
                }
                catch(org.json.JSONException je){
                    System.err.println("An error is occourred in MultiPolygon JSON creation.");
                }
            }
            tmp.put(serName, dcInfoes);
        }
        return tmp;
    }
    
    /**
     * It returns an HashMap that correlate ServiceGroup name with the ArrayList that contains 
     * the ArrayList of String representation of JSONObject of the user credential for each datacenter.
     * These Datacenter set (represented by the inner ArrayList) is the credential for datacenter existent inside the shape
     * descript in geoshape; the external ArrayList is the set of all previous entity retrieved for each shape.
     * The external ArrayList is a priority ordered List.
     * @param dcInfoesMap
     * @param db
     * @param tenant
     * @param username
     * @param password
     * @return 
     */
    public HashMap<String,ArrayList<ArrayList<OpenstackInfoContainer>>> managementRetrieveCredential(HashMap<String,ArrayList<ArrayList<String>>> dcInfoesMap,MDBInt.DBMongo db,String tenant,String username,String password,String region){
        HashMap<String,ArrayList<ArrayList<OpenstackInfoContainer>>> tmp=new HashMap<String,ArrayList<ArrayList<OpenstackInfoContainer>>>();//mappa contenente associazione nome shape con federatedUser credential for each Datacenter
        String serName="";
        Iterator it=dcInfoesMap.keySet().iterator();
        while(it.hasNext()){
            serName=(String)it.next();
            ArrayList<ArrayList<String>> tmp2=(ArrayList<ArrayList<String>>)dcInfoesMap.get(serName);
            ArrayList<ArrayList<OpenstackInfoContainer>> crtmp2=new ArrayList<ArrayList<OpenstackInfoContainer>>();
            for(int ind_ex=0;ind_ex<tmp2.size();ind_ex++){
                ArrayList<String> tmp3=(ArrayList<String>)dcInfoesMap.get(serName).get(ind_ex);
                ArrayList<OpenstackInfoContainer> crtmp3=new ArrayList<OpenstackInfoContainer>();
                for(int ind_int=0;ind_int<tmp3.size();ind_int++){
                    JSONObject j=null,jj=null;
                    OpenstackInfoContainer credential=null;
                    try{
                        j=new JSONObject((String)tmp3.get(ind_int));
                        jj=new JSONObject(db.getFederatedCredential(tenant, username, password,j.getString("cloudId") ));
                        credential=new OpenstackInfoContainer(j.getString("idmEndpoint"),tenant,jj.getString("federatedUser"),jj.getString("federatedPassword"),region);
                    }
                    catch(org.json.JSONException je){
                        System.err.println("An error is occourred in MultiPolygon JSON creation.");
                    }
                    crtmp3.add(credential);
                }
                crtmp2.add(crtmp3);
            }
            tmp.put(serName, crtmp2);
        }
        return tmp;
    }
    
    /**
     * 
     * @param tenant
     * @param template
     * @param endpoint
     * @param user
     * @param psw
     * @return 
     */
    public boolean stackInstantiate(String template,OpenstackInfoContainer credential){
        Heat h=new Heat(credential.getEndpoint(), credential.getUser(),credential.getTenant(),credential.getPassword());
        try{
            Stack s=h.createStack(credential.getTenant(), template);//restituirà un oggetto di tipo stack che all'interno possiede
                    // lo stato ottenuto , verificare se lo status (getStatus) è di tipo "CREATE_COMPLETE" o "CREATE_FAILED"
            if(s.getStatus().equals("CREATE_FAILED")){
                System.err.println("An error is occurred in stack creation phase.Verify Federated Openstack state.\n"
                        + " Stack creation Operation har returned CREATE_FAILED");
                return false;
            }
                
        }
        catch(Exception e){
            System.err.println("An error is occurred in stack creation phase.");
            return false;
        }
        return true;
    }
    
    /**
     * 
     * @param stackName
     * @param endpoint
     * @param tenant
     * @param user
     * @param password
     * @param region
     * @param first
     * @param m
     * @return 
     */
    public HashMap<String,ArrayList<Port>> sendShutSignalStack4DeployAction(String stackName,OpenstackInfoContainer credential,
            boolean first,DBMongo m)
    {
        NovaTest nova=new NovaTest(credential.getEndpoint(),credential.getTenant(), credential.getUser(),credential.getPassword(),credential.getRegion());
        NeutronTest neutron=new NeutronTest(credential.getEndpoint(),credential.getTenant(), credential.getUser(),credential.getPassword(),credential.getRegion());
        Heat heat=new Heat(credential.getEndpoint(), credential.getUser(),credential.getTenant(),credential.getPassword());
        HashMap<String,ArrayList<Port>> mapResNet=new HashMap<String,ArrayList<Port>>();
        List<? extends Resource> l = heat.getResource(stackName);
        Iterator it_res=l.iterator();
        while(it_res.hasNext()){
            Resource r = (Resource)it_res.next();
            String id_res=r.getPhysicalResourceId();
            if(!first)
                nova.stopVm(id_res);
            ArrayList<Port> arPort=neutron.getPortFromDeviceId(id_res);
            //inserire in quest'array la lista delle porte di quella VM
            mapResNet.put(id_res,arPort);
            Iterator it_po=arPort.iterator();
            while(it_po.hasNext()){
                m.insertPortInfo(credential.getTenant(), neutron.portToString((Port)it_po.next()));
            }
        }
        return mapResNet;
    }
    
    public void sufferingProcedure(String vm,String tenant,String userFederation,String pswFederation,DBMongo m,int element,String region){
        //spegnimento vm
        ////recupero runtimeinfo
        String runTime=m.getRunTimeInfo(tenant, vm);
        String idClo="",endpoint="",cred="", twinUUID="";
        OpenstackInfoContainer credential=null,credential2=null;
        JSONObject credJobj=null,runJobj=null;
        try{
            runJobj=new JSONObject(runTime);
            ////recupero idCloud
            idClo=runJobj.getString("idCloud");
            ////recupero le credenziali passando da quelle di federazione
            cred=m.getFederatedCredential(tenant, userFederation, pswFederation,idClo);
            credJobj=new JSONObject(cred);
            endpoint=(new JSONObject(m.getDatacenter(tenant, idClo))).getString("idmEndpoint");
            credential=new OpenstackInfoContainer(endpoint,tenant,credJobj.getString("federatedUser"),credJobj.getString("federatedPassword"),region);
        }
        catch(JSONException je){
             System.err.println("An error is occourred in JSON crederntial manipulation.");
        }
        ////spengo la vm
        NovaTest nova=new NovaTest(credential.getEndpoint(),credential.getTenant(), credential.getUser(),credential.getPassword(),credential.getRegion());
        nova.stopVm(vm);
        // identificazione nuova vm
        ArrayList<String> vmTwins= m.findResourceMate(tenant, vm);
        try{
            JSONObject j=new JSONObject(vmTwins.get(element));
            twinUUID=j.getString("phisicalResourceId");
            ////recupero runtimeinfo
            runTime=m.getRunTimeInfo(tenant, twinUUID);
            runJobj=new JSONObject(runTime);
            ////recupero idCloud
            idClo=runJobj.getString("idCloud");
            ////recupero le credenziali passando da quelle di federazione
            cred=m.getFederatedCredential(tenant, userFederation, pswFederation,idClo);
            credJobj=new JSONObject(cred);
            endpoint=(new JSONObject(m.getDatacenter(tenant, idClo))).getString("idmEndpoint");
            credential2=new OpenstackInfoContainer(endpoint,tenant,credJobj.getString("federatedUser"),credJobj.getString("federatedPassword"),region);
        }
        catch(JSONException je){
             System.err.println("An error is occourred in JSON crederntial manipulation.");
        }
        //accensione vm idenitificata
        nova=new NovaTest(credential2.getEndpoint(),credential2.getTenant(), credential2.getUser(),credential2.getPassword(),credential2.getRegion());
        nova.startVm(twinUUID);
        //restituzione dettagli vm spenta- rete, vm accesa- rete
        System.out.println("Network infoes of the shutted down VM(identified by UUID:"+vm+"):");
        Iterator it_tmpar=m.getportinfoes(tenant, vm).iterator();
        while(it_tmpar.hasNext())
            System.out.println((String)it_tmpar.next());
        System.out.println("Network infoes of the twin VM started(identified by UUID:"+twinUUID+"):");
        it_tmpar=m.getportinfoes(tenant, twinUUID).iterator();
        while(it_tmpar.hasNext())
            System.out.println((String)it_tmpar.next());
    }
            
    
    
    /**
     * Testing function for analyzing Manifest and trasforming it in YAML.
     * @param mnam
     * @param root 
     
    public void test(String mnam,String root){
        ManifestManager mm=(ManifestManager)OrchestrationManager.mapManifestThr.get(mnam);
        this.manageYAMLcreation(mm, root);
    }
    */
}
