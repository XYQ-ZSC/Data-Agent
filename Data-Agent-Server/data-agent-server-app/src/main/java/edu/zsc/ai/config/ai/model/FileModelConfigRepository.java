package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.domain.service.ai.model.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component @Primary @RequiredArgsConstructor
@ConditionalOnProperty(name="ai.model-routing.persistence-enabled",havingValue="true")
public class FileModelConfigRepository implements ModelConfigRepository {
    private final FileModelStore store;
    private final MultiProviderProperties properties;
    public boolean enabled(){return properties.getModelRouting().isEnabled();}
    public int maxCallsPerRun(){return properties.getModelRouting().getMaxCallsPerRun();}
    public <T>T inSnapshot(ModelAccessContext a,java.util.function.Supplier<T> action){return store.snapshot(a,action);}
    public List<ResolvedModelConfig> list(ModelAccessContext a){return store.read(a,s->s.models.keySet().stream().map(id->resolve(s,id,a)).filter(Objects::nonNull).toList());}
    public ResolvedModelConfig find(String id){return find(id,ModelPurpose.MAIN_CHAT);}
    public ResolvedModelConfig find(String id,ModelPurpose purpose){
        ModelAccessContext a;
        try {
            if(id!=null && id.startsWith("startup/") && properties.getModelRouting().isLegacyAliasesEnabled()) {
                var parts=id.split("/",-1);if(parts.length!=4 || !parts[2].matches("[1-9][0-9]{0,18}") || !parts[3].matches("[A-Za-z0-9._-]+"))return null;
                a=new ModelAccessContext(0,WorkspaceTypeEnum.valueOf(parts[1]),Long.parseLong(parts[2]));
            }else{a=ModelResourceId.scope(id);if(!ModelResourceId.belongs(id,'m',a))return null;}
        }catch(RuntimeException e){return null;}
        var scope=a;
        try{return store.read(scope,s->resolve(s,id.startsWith("startup/")?s.aliases.get(id):id,scope,purpose));}
        catch(ModelManagementException e){if(e.status()==org.springframework.http.HttpStatus.NOT_FOUND)return null;throw e;}
    }
    public String binding(ModelAccessContext a,ModelPurpose purpose){return store.read(a,s->{var b=s.bindings.get(purpose);return b==null?null:b.modelConfigId();});}
    public String alias(ModelAccessContext a,String name){return properties.getModelRouting().isLegacyAliasesEnabled()?store.read(a,s->s.aliases.get(name)):null;}
    public boolean permitsSnapshot(ResolvedModelConfig snapshot){
        return store.read(snapshot.access(),s->{
            var m=s.models.get(snapshot.modelConfigId());var c=s.connections.get(snapshot.connectionId());
            var k=c==null?null:s.credentials.get(c.credentialId());
            return m!=null && m.enabled() && c!=null && c.enabled() && k!=null
                    && snapshot.credentialRef().equals("credential:"+k.id()) && snapshot.credentialVersion()==k.version();
        });
    }
    private ResolvedModelConfig resolve(FileModelState s,String id,ModelAccessContext a){
        return resolve(s,id,a,ModelPurpose.MAIN_CHAT);
    }
    private ResolvedModelConfig resolve(FileModelState s,String id,ModelAccessContext a,ModelPurpose purpose){
        var m=s.models.get(id);if(m==null || !m.enabled())return null;
        var c=s.connections.get(m.connectionId());if(c==null || !c.enabled())return null;
        var k=s.credentials.get(c.credentialId());if(k==null)return null;
        Set<String> hosts=new HashSet<>(c.allowedPrivateHosts());properties.getModelRouting().getAllowedPrivateHosts().forEach(host->hosts.add(host.toLowerCase(Locale.ROOT)));
        var options=m.options();
        int context=m.tokenLimits()!=null && m.tokenLimits().maxContextTokens()==null?ModelTokenPolicy.context(m.modelId(),null):m.maxContextTokens();
        var specification=QwenModelSpecifications.find(m.modelId(),c.compatibilityProfile());
        if(specification!=null)context=Math.min(context,specification.contextTokens());
        Integer requested=m.tokenLimits()==null?options.maxOutputTokens():m.tokenLimits().maxOutputTokens();
        if(specification!=null && requested!=null)requested=Math.min(requested,specification.outputLimit(options.thinkingMode()));
        if(m.tokenLimits()!=null)options=new ResolvedModelConfig.EffectiveOptions(options.thinkingMode(),options.thinkingBudget(),options.temperature(),
                ModelTokenPolicy.output(m.modelId(),c.compatibilityProfile(),requested,context,options.thinkingMode(),options.thinkingBudget(),purpose),options.reasoningEffort());
        else if(specification!=null && options.maxOutputTokens()!=null)options=new ResolvedModelConfig.EffectiveOptions(options.thinkingMode(),options.thinkingBudget(),options.temperature(),
                Math.min(context-1,Math.min(options.maxOutputTokens(),specification.outputLimit(options.thinkingMode()))),options.reasoningEffort());
        return new ResolvedModelConfig(m.id(),m.version(),c.id(),c.version(),a,c.provider(),c.apiStyle(),m.modelId(),m.name(),ModelEndpointPolicy.normalize(c.endpoint()),
                "credential:"+k.id(),k.version(),m.capabilityProfile(),c.compatibilityProfile(),options,context,Math.min(m.memoryThreshold(),context-1),m.chatVisible(),hosts);
    }
}
