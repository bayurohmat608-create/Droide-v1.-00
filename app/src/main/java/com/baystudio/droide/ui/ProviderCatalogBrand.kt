package com.baystudio.droide.ui

import androidx.annotation.DrawableRes
import com.baystudio.droide.R

// Pinned upstream identity assets: third_party/models-dev-logos/manifest.json.
@DrawableRes
internal fun providerCatalogLogo(id: String): Int? = when (id) {
    "302ai" -> R.drawable.provider_catalog_302ai
    "abacus" -> R.drawable.provider_catalog_abacus
    "abliteration-ai" -> R.drawable.provider_catalog_abliteration_2d_ai
    "above" -> R.drawable.provider_catalog_above
    "agentrouter" -> R.drawable.provider_catalog_agentrouter
    "agnes" -> R.drawable.provider_catalog_agnes
    "ai-router" -> R.drawable.provider_catalog_ai_2d_router
    "ai21" -> R.drawable.provider_catalog_ai21
    "aiand" -> R.drawable.provider_catalog_aiand
    "aihubmix" -> R.drawable.provider_catalog_aihubmix
    "ainetcafe" -> R.drawable.provider_catalog_ainetcafe
    "aixy" -> R.drawable.provider_catalog_aixy
    "aki-io" -> R.drawable.provider_catalog_aki_2d_io
    "alibaba" -> R.drawable.provider_catalog_alibaba
    "alibaba-cn" -> R.drawable.provider_catalog_alibaba_2d_cn
    "alibaba-coding-plan" -> R.drawable.provider_catalog_alibaba_2d_coding_2d_plan
    "alibaba-coding-plan-cn" -> R.drawable.provider_catalog_alibaba_2d_coding_2d_plan_2d_cn
    "alibaba-token-plan" -> R.drawable.provider_catalog_alibaba_2d_token_2d_plan
    "alibaba-token-plan-cn" -> R.drawable.provider_catalog_alibaba_2d_cn
    "amazon-bedrock" -> R.drawable.provider_catalog_amazon_2d_bedrock
    "ambient" -> R.drawable.provider_catalog_ambient
    "amd" -> R.drawable.provider_catalog_amd
    "anthropic" -> R.drawable.provider_catalog_anthropic
    "anyapi" -> R.drawable.provider_catalog_anyapi
    "arcee" -> R.drawable.provider_catalog_arcee
    "atomic-chat" -> R.drawable.provider_catalog_atomic_2d_chat
    "auriko" -> R.drawable.provider_catalog_auriko
    "azure" -> R.drawable.provider_catalog_azure
    "azure-cognitive-services" -> R.drawable.provider_catalog_azure_2d_cognitive_2d_services
    "bailing" -> R.drawable.provider_catalog_bailing
    "baseten" -> R.drawable.provider_catalog_baseten
    "bee" -> R.drawable.provider_catalog_bee
    "berget" -> R.drawable.provider_catalog_berget
    "blueclaw" -> R.drawable.provider_catalog_blueclaw
    "bothub" -> R.drawable.provider_catalog_bothub
    "cerebras" -> R.drawable.provider_catalog_cerebras
    "chutes" -> R.drawable.provider_catalog_chutes
    "clarifai" -> R.drawable.provider_catalog_clarifai
    "claudinio" -> R.drawable.provider_catalog_claudinio
    "cline-pass" -> R.drawable.provider_catalog_cline_2d_pass
    "cloudferro-sherlock" -> R.drawable.provider_catalog_cloudferro_2d_sherlock
    "cloudflare-ai-gateway" -> R.drawable.provider_catalog_cloudflare_2d_ai_2d_gateway
    "cloudflare-workers-ai" -> R.drawable.provider_catalog_cloudflare_2d_workers_2d_ai
    "cohere" -> R.drawable.provider_catalog_cohere
    "coralbricks" -> R.drawable.provider_catalog_coralbricks
    "crossmodel" -> R.drawable.provider_catalog_crossmodel
    "crusoe" -> R.drawable.provider_catalog_crusoe
    "daoxe" -> R.drawable.provider_catalog_daoxe
    "databricks" -> R.drawable.provider_catalog_databricks
    "deepinfra" -> R.drawable.provider_catalog_deepinfra
    "deepseek" -> R.drawable.provider_catalog_deepseek
    "digitalocean" -> R.drawable.provider_catalog_digitalocean
    "dinference" -> R.drawable.provider_catalog_dinference
    "drun" -> R.drawable.provider_catalog_drun
    "ebcloud" -> R.drawable.provider_catalog_ebcloud
    "echo" -> R.drawable.provider_catalog_echo
    "edenai" -> R.drawable.provider_catalog_edenai
    "empiriolabs" -> R.drawable.provider_catalog_empiriolabs
    "evroc" -> R.drawable.provider_catalog_evroc
    "fastrouter" -> R.drawable.provider_catalog_fastrouter
    "fireworks" -> R.drawable.provider_catalog_fireworks_2d_ai
    "fireworks-ai" -> R.drawable.provider_catalog_fireworks_2d_ai
    "freemodel" -> R.drawable.provider_catalog_freemodel
    "friendli" -> R.drawable.provider_catalog_friendli
    "frogbot" -> R.drawable.provider_catalog_frogbot
    "gemini" -> R.drawable.provider_catalog_google
    "github" -> R.drawable.provider_catalog_github_2d_copilot
    "github-copilot" -> R.drawable.provider_catalog_github_2d_copilot
    "gitlab" -> R.drawable.provider_catalog_gitlab
    "gmicloud" -> R.drawable.provider_catalog_gmicloud
    "google" -> R.drawable.provider_catalog_google
    "google-vertex" -> R.drawable.provider_catalog_google_2d_vertex
    "google-vertex-anthropic" -> R.drawable.provider_catalog_google_2d_vertex_2d_anthropic
    "greenpt" -> R.drawable.provider_catalog_greenpt
    "groq" -> R.drawable.provider_catalog_groq
    "helicone" -> R.drawable.provider_catalog_helicone
    "hetzner" -> R.drawable.provider_catalog_hetzner
    "hpc-ai" -> R.drawable.provider_catalog_hpc_2d_ai
    "huggingface" -> R.drawable.provider_catalog_huggingface
    "hyper" -> R.drawable.provider_catalog_hyper
    "iflowcn" -> R.drawable.provider_catalog_iflowcn
    "impossibl" -> R.drawable.provider_catalog_impossibl
    "inception" -> R.drawable.provider_catalog_inception
    "inceptron" -> R.drawable.provider_catalog_inceptron
    "inco" -> R.drawable.provider_catalog_inco
    "infer" -> R.drawable.provider_catalog_infer
    "inference" -> R.drawable.provider_catalog_inference
    "inferx" -> R.drawable.provider_catalog_inferx
    "infomaniak" -> R.drawable.provider_catalog_infomaniak
    "io-net" -> R.drawable.provider_catalog_io_2d_net
    "iteracompute" -> R.drawable.provider_catalog_iteracompute
    "jalapeno" -> R.drawable.provider_catalog_jalapeno
    "jiekou" -> R.drawable.provider_catalog_jiekou
    "kenari" -> R.drawable.provider_catalog_kenari
    "kilo" -> R.drawable.provider_catalog_kilo
    "kimi-code-plan-cn" -> R.drawable.provider_catalog_kimi_2d_code_2d_plan_2d_cn
    "kimi-code-plan-global" -> R.drawable.provider_catalog_kimi_2d_code_2d_plan_2d_global
    "klokintegration" -> R.drawable.provider_catalog_klokintegration
    "kosmik" -> R.drawable.provider_catalog_kosmik
    "kuae-cloud-coding-plan" -> R.drawable.provider_catalog_kuae_2d_cloud_2d_coding_2d_plan
    "lilac" -> R.drawable.provider_catalog_lilac
    "llama" -> R.drawable.provider_catalog_llama
    "llmgateway" -> R.drawable.provider_catalog_llmgateway
    "llmgateway-providers" -> R.drawable.provider_catalog_llmgateway_2d_providers
    "llmtech" -> R.drawable.provider_catalog_llmtech
    "llmtr" -> R.drawable.provider_catalog_llmtr
    "lmstudio" -> R.drawable.provider_catalog_lmstudio
    "longcat" -> R.drawable.provider_catalog_longcat
    "lucidquery" -> R.drawable.provider_catalog_lucidquery
    "lynkr" -> R.drawable.provider_catalog_lynkr
    "meganova" -> R.drawable.provider_catalog_meganova
    "melious" -> R.drawable.provider_catalog_melious
    "merge-gateway" -> R.drawable.provider_catalog_merge_2d_gateway
    "meta" -> R.drawable.provider_catalog_meta
    "minimax" -> R.drawable.provider_catalog_minimax
    "minimax-cn" -> R.drawable.provider_catalog_minimax_2d_cn
    "minimax-cn-coding-plan" -> R.drawable.provider_catalog_minimax_2d_cn_2d_coding_2d_plan
    "minimax-coding-plan" -> R.drawable.provider_catalog_minimax_2d_coding_2d_plan
    "mistral" -> R.drawable.provider_catalog_mistral
    "mixlayer" -> R.drawable.provider_catalog_mixlayer
    "moark" -> R.drawable.provider_catalog_moark
    "modal" -> R.drawable.provider_catalog_modal
    "model-oracle-ai" -> R.drawable.provider_catalog_model_2d_oracle_2d_ai
    "modelis" -> R.drawable.provider_catalog_modelis
    "modelscope" -> R.drawable.provider_catalog_modelscope
    "moonshotai" -> R.drawable.provider_catalog_moonshotai
    "moonshotai-cn" -> R.drawable.provider_catalog_moonshotai_2d_cn
    "morph" -> R.drawable.provider_catalog_morph
    "nan" -> R.drawable.provider_catalog_nan
    "nano-gpt" -> R.drawable.provider_catalog_nano_2d_gpt
    "nearai" -> R.drawable.provider_catalog_nearai
    "nebius" -> R.drawable.provider_catalog_nebius
    "neon" -> R.drawable.provider_catalog_neon
    "neosmith" -> R.drawable.provider_catalog_neosmith
    "neuralwatt" -> R.drawable.provider_catalog_neuralwatt
    "nova" -> R.drawable.provider_catalog_nova
    "novita-ai" -> R.drawable.provider_catalog_novita_2d_ai
    "nvidia" -> R.drawable.provider_catalog_nvidia
    "oci" -> R.drawable.provider_catalog_oci
    "ofox" -> R.drawable.provider_catalog_ofox
    "ollama-cloud" -> R.drawable.provider_catalog_ollama_2d_cloud
    "openai" -> R.drawable.provider_catalog_openai
    "opencode" -> R.drawable.provider_catalog_opencode
    "opencode-go" -> R.drawable.provider_catalog_opencode_2d_go
    "openreason" -> R.drawable.provider_catalog_openreason
    "openrouter" -> R.drawable.provider_catalog_openrouter
    "opper" -> R.drawable.provider_catalog_opper
    "orcarouter" -> R.drawable.provider_catalog_orcarouter
    "ovhcloud" -> R.drawable.provider_catalog_ovhcloud
    "pareto" -> R.drawable.provider_catalog_pareto
    "pendra" -> R.drawable.provider_catalog_pendra
    "perplexity" -> R.drawable.provider_catalog_perplexity
    "perplexity-agent" -> R.drawable.provider_catalog_perplexity_2d_agent
    "pioneer" -> R.drawable.provider_catalog_pioneer
    "poe" -> R.drawable.provider_catalog_poe
    "poolside" -> R.drawable.provider_catalog_poolside
    "privatemode-ai" -> R.drawable.provider_catalog_privatemode_2d_ai
    "qihang-ai" -> R.drawable.provider_catalog_qihang_2d_ai
    "qiniu-ai" -> R.drawable.provider_catalog_qiniu_2d_ai
    "qvac" -> R.drawable.provider_catalog_qvac
    "regolo-ai" -> R.drawable.provider_catalog_regolo_2d_ai
    "routing-run" -> R.drawable.provider_catalog_routing_2d_run
    "runinfra" -> R.drawable.provider_catalog_runinfra
    "sakana" -> R.drawable.provider_catalog_sakana
    "salad-cloud" -> R.drawable.provider_catalog_salad_2d_cloud
    "scaleway" -> R.drawable.provider_catalog_scaleway
    "scnet-token-plan" -> R.drawable.provider_catalog_scnet_2d_token_2d_plan
    "scx-ai" -> R.drawable.provider_catalog_scx_2d_ai
    "sensenova" -> R.drawable.provider_catalog_sensenova
    "siliconflow" -> R.drawable.provider_catalog_siliconflow
    "siliconflow-cn" -> R.drawable.provider_catalog_siliconflow_2d_cn
    "snowflake-cortex" -> R.drawable.provider_catalog_snowflake_2d_cortex
    "stackit" -> R.drawable.provider_catalog_stackit
    "standardcompute" -> R.drawable.provider_catalog_standardcompute
    "stepfun" -> R.drawable.provider_catalog_stepfun
    "stepfun-ai" -> R.drawable.provider_catalog_stepfun_2d_ai
    "stepfun-ai-step-plan" -> R.drawable.provider_catalog_stepfun_2d_ai_2d_step_2d_plan
    "stepfun-step-plan" -> R.drawable.provider_catalog_stepfun_2d_step_2d_plan
    "subconscious" -> R.drawable.provider_catalog_subconscious
    "submodel" -> R.drawable.provider_catalog_submodel
    "tempr" -> R.drawable.provider_catalog_tempr
    "tencent-coding-plan" -> R.drawable.provider_catalog_tencent_2d_coding_2d_plan
    "tencent-token-plan" -> R.drawable.provider_catalog_tencent_2d_token_2d_plan
    "tencent-tokenhub" -> R.drawable.provider_catalog_tencent_2d_tokenhub
    "tensorx" -> R.drawable.provider_catalog_tensorx
    "the-grid-ai" -> R.drawable.provider_catalog_the_2d_grid_2d_ai
    "thinkingmachines" -> R.drawable.provider_catalog_thinkingmachines
    "tinfoil" -> R.drawable.provider_catalog_tinfoil
    "together" -> R.drawable.provider_catalog_togetherai
    "togetherai" -> R.drawable.provider_catalog_togetherai
    "tokengo" -> R.drawable.provider_catalog_tokengo
    "tokenrouter" -> R.drawable.provider_catalog_tokenrouter
    "trustedrouter" -> R.drawable.provider_catalog_trustedrouter
    "umans-ai" -> R.drawable.provider_catalog_umans_2d_ai
    "umans-ai-coding-plan" -> R.drawable.provider_catalog_umans_2d_ai_2d_coding_2d_plan
    "unorouter" -> R.drawable.provider_catalog_unorouter
    "upstage" -> R.drawable.provider_catalog_upstage
    "v0" -> R.drawable.provider_catalog_v0
    "vancine" -> R.drawable.provider_catalog_vancine
    "venice" -> R.drawable.provider_catalog_venice
    "vercel" -> R.drawable.provider_catalog_vercel
    "vispark" -> R.drawable.provider_catalog_vispark
    "vivgrid" -> R.drawable.provider_catalog_vivgrid
    "volcengine" -> R.drawable.provider_catalog_volcengine
    "volcengine-coding-plan" -> R.drawable.provider_catalog_volcengine_2d_coding_2d_plan
    "vultr" -> R.drawable.provider_catalog_vultr
    "wafer.ai" -> R.drawable.provider_catalog_wafer_2e_ai
    "wallaby" -> R.drawable.provider_catalog_wallaby
    "wandb" -> R.drawable.provider_catalog_wandb
    "watsonx" -> R.drawable.provider_catalog_watsonx
    "x-ai" -> R.drawable.provider_catalog_xai
    "xai" -> R.drawable.provider_catalog_xai
    "xiaomi" -> R.drawable.provider_catalog_xiaomi
    "xiaomi-token-plan-ams" -> R.drawable.provider_catalog_xiaomi_2d_token_2d_plan_2d_ams
    "xiaomi-token-plan-cn" -> R.drawable.provider_catalog_xiaomi_2d_token_2d_plan_2d_cn
    "xiaomi-token-plan-sgp" -> R.drawable.provider_catalog_xiaomi_2d_token_2d_plan_2d_sgp
    "xpersona" -> R.drawable.provider_catalog_xpersona
    "zai" -> R.drawable.provider_catalog_zai
    "zai-coding-plan" -> R.drawable.provider_catalog_zai_2d_coding_2d_plan
    "zeldoc" -> R.drawable.provider_catalog_zeldoc
    "zenifra" -> R.drawable.provider_catalog_zenifra
    "zenmux" -> R.drawable.provider_catalog_zenmux
    "zhipuai" -> R.drawable.provider_catalog_zhipuai
    "zhipuai-coding-plan" -> R.drawable.provider_catalog_zhipuai_2d_coding_2d_plan
    else -> null
}

internal fun isMonochromeProviderCatalogLogo(@DrawableRes logo: Int): Boolean = when (logo) {
    R.drawable.provider_catalog_abacus,
    R.drawable.provider_catalog_abliteration_2d_ai,
    R.drawable.provider_catalog_above,
    R.drawable.provider_catalog_agentrouter,
    R.drawable.provider_catalog_agnes,
    R.drawable.provider_catalog_ai_2d_router,
    R.drawable.provider_catalog_ai21,
    R.drawable.provider_catalog_aiand,
    R.drawable.provider_catalog_aihubmix,
    R.drawable.provider_catalog_ainetcafe,
    R.drawable.provider_catalog_aixy,
    R.drawable.provider_catalog_aki_2d_io,
    R.drawable.provider_catalog_alibaba_2d_cn,
    R.drawable.provider_catalog_alibaba_2d_coding_2d_plan_2d_cn,
    R.drawable.provider_catalog_alibaba_2d_coding_2d_plan,
    R.drawable.provider_catalog_alibaba_2d_token_2d_plan,
    R.drawable.provider_catalog_alibaba,
    R.drawable.provider_catalog_amazon_2d_bedrock,
    R.drawable.provider_catalog_ambient,
    R.drawable.provider_catalog_amd,
    R.drawable.provider_catalog_anthropic,
    R.drawable.provider_catalog_anyapi,
    R.drawable.provider_catalog_arcee,
    R.drawable.provider_catalog_auriko,
    R.drawable.provider_catalog_azure_2d_cognitive_2d_services,
    R.drawable.provider_catalog_azure,
    R.drawable.provider_catalog_bailing,
    R.drawable.provider_catalog_baseten,
    R.drawable.provider_catalog_bee,
    R.drawable.provider_catalog_berget,
    R.drawable.provider_catalog_blueclaw,
    R.drawable.provider_catalog_bothub,
    R.drawable.provider_catalog_cerebras,
    R.drawable.provider_catalog_clarifai,
    R.drawable.provider_catalog_claudinio,
    R.drawable.provider_catalog_cloudferro_2d_sherlock,
    R.drawable.provider_catalog_cloudflare_2d_ai_2d_gateway,
    R.drawable.provider_catalog_cloudflare_2d_workers_2d_ai,
    R.drawable.provider_catalog_cohere,
    R.drawable.provider_catalog_coralbricks,
    R.drawable.provider_catalog_crossmodel,
    R.drawable.provider_catalog_crusoe,
    R.drawable.provider_catalog_daoxe,
    R.drawable.provider_catalog_databricks,
    R.drawable.provider_catalog_deepinfra,
    R.drawable.provider_catalog_deepseek,
    R.drawable.provider_catalog_digitalocean,
    R.drawable.provider_catalog_dinference,
    R.drawable.provider_catalog_drun,
    R.drawable.provider_catalog_ebcloud,
    R.drawable.provider_catalog_echo,
    R.drawable.provider_catalog_edenai,
    R.drawable.provider_catalog_empiriolabs,
    R.drawable.provider_catalog_evroc,
    R.drawable.provider_catalog_fastrouter,
    R.drawable.provider_catalog_fireworks_2d_ai,
    R.drawable.provider_catalog_freemodel,
    R.drawable.provider_catalog_friendli,
    R.drawable.provider_catalog_github_2d_copilot,
    R.drawable.provider_catalog_gitlab,
    R.drawable.provider_catalog_google_2d_vertex_2d_anthropic,
    R.drawable.provider_catalog_google_2d_vertex,
    R.drawable.provider_catalog_google,
    R.drawable.provider_catalog_greenpt,
    R.drawable.provider_catalog_groq,
    R.drawable.provider_catalog_helicone,
    R.drawable.provider_catalog_hetzner,
    R.drawable.provider_catalog_huggingface,
    R.drawable.provider_catalog_hyper,
    R.drawable.provider_catalog_iflowcn,
    R.drawable.provider_catalog_impossibl,
    R.drawable.provider_catalog_inception,
    R.drawable.provider_catalog_inceptron,
    R.drawable.provider_catalog_inco,
    R.drawable.provider_catalog_infer,
    R.drawable.provider_catalog_inference,
    R.drawable.provider_catalog_inferx,
    R.drawable.provider_catalog_infomaniak,
    R.drawable.provider_catalog_io_2d_net,
    R.drawable.provider_catalog_iteracompute,
    R.drawable.provider_catalog_jalapeno,
    R.drawable.provider_catalog_jiekou,
    R.drawable.provider_catalog_kenari,
    R.drawable.provider_catalog_kilo,
    R.drawable.provider_catalog_kimi_2d_code_2d_plan_2d_cn,
    R.drawable.provider_catalog_kimi_2d_code_2d_plan_2d_global,
    R.drawable.provider_catalog_klokintegration,
    R.drawable.provider_catalog_kosmik,
    R.drawable.provider_catalog_kuae_2d_cloud_2d_coding_2d_plan,
    R.drawable.provider_catalog_lilac,
    R.drawable.provider_catalog_llama,
    R.drawable.provider_catalog_llmgateway_2d_providers,
    R.drawable.provider_catalog_llmgateway,
    R.drawable.provider_catalog_llmtech,
    R.drawable.provider_catalog_llmtr,
    R.drawable.provider_catalog_lmstudio,
    R.drawable.provider_catalog_longcat,
    R.drawable.provider_catalog_lucidquery,
    R.drawable.provider_catalog_lynkr,
    R.drawable.provider_catalog_meganova,
    R.drawable.provider_catalog_melious,
    R.drawable.provider_catalog_merge_2d_gateway,
    R.drawable.provider_catalog_meta,
    R.drawable.provider_catalog_minimax_2d_cn_2d_coding_2d_plan,
    R.drawable.provider_catalog_minimax_2d_cn,
    R.drawable.provider_catalog_minimax_2d_coding_2d_plan,
    R.drawable.provider_catalog_minimax,
    R.drawable.provider_catalog_mistral,
    R.drawable.provider_catalog_mixlayer,
    R.drawable.provider_catalog_moark,
    R.drawable.provider_catalog_modal,
    R.drawable.provider_catalog_model_2d_oracle_2d_ai,
    R.drawable.provider_catalog_modelis,
    R.drawable.provider_catalog_modelscope,
    R.drawable.provider_catalog_moonshotai_2d_cn,
    R.drawable.provider_catalog_moonshotai,
    R.drawable.provider_catalog_nan,
    R.drawable.provider_catalog_nano_2d_gpt,
    R.drawable.provider_catalog_nearai,
    R.drawable.provider_catalog_nebius,
    R.drawable.provider_catalog_neon,
    R.drawable.provider_catalog_neosmith,
    R.drawable.provider_catalog_neuralwatt,
    R.drawable.provider_catalog_nova,
    R.drawable.provider_catalog_novita_2d_ai,
    R.drawable.provider_catalog_nvidia,
    R.drawable.provider_catalog_oci,
    R.drawable.provider_catalog_ofox,
    R.drawable.provider_catalog_ollama_2d_cloud,
    R.drawable.provider_catalog_openai,
    R.drawable.provider_catalog_opencode_2d_go,
    R.drawable.provider_catalog_opencode,
    R.drawable.provider_catalog_openreason,
    R.drawable.provider_catalog_openrouter,
    R.drawable.provider_catalog_opper,
    R.drawable.provider_catalog_ovhcloud,
    R.drawable.provider_catalog_pareto,
    R.drawable.provider_catalog_pendra,
    R.drawable.provider_catalog_perplexity_2d_agent,
    R.drawable.provider_catalog_perplexity,
    R.drawable.provider_catalog_pioneer,
    R.drawable.provider_catalog_poe,
    R.drawable.provider_catalog_poolside,
    R.drawable.provider_catalog_privatemode_2d_ai,
    R.drawable.provider_catalog_qihang_2d_ai,
    R.drawable.provider_catalog_qiniu_2d_ai,
    R.drawable.provider_catalog_qvac,
    R.drawable.provider_catalog_routing_2d_run,
    R.drawable.provider_catalog_runinfra,
    R.drawable.provider_catalog_salad_2d_cloud,
    R.drawable.provider_catalog_scaleway,
    R.drawable.provider_catalog_scnet_2d_token_2d_plan,
    R.drawable.provider_catalog_scx_2d_ai,
    R.drawable.provider_catalog_sensenova,
    R.drawable.provider_catalog_siliconflow_2d_cn,
    R.drawable.provider_catalog_siliconflow,
    R.drawable.provider_catalog_snowflake_2d_cortex,
    R.drawable.provider_catalog_stackit,
    R.drawable.provider_catalog_standardcompute,
    R.drawable.provider_catalog_stepfun_2d_ai_2d_step_2d_plan,
    R.drawable.provider_catalog_stepfun_2d_ai,
    R.drawable.provider_catalog_stepfun_2d_step_2d_plan,
    R.drawable.provider_catalog_stepfun,
    R.drawable.provider_catalog_subconscious,
    R.drawable.provider_catalog_submodel,
    R.drawable.provider_catalog_tempr,
    R.drawable.provider_catalog_tencent_2d_coding_2d_plan,
    R.drawable.provider_catalog_tencent_2d_token_2d_plan,
    R.drawable.provider_catalog_tencent_2d_tokenhub,
    R.drawable.provider_catalog_tensorx,
    R.drawable.provider_catalog_the_2d_grid_2d_ai,
    R.drawable.provider_catalog_thinkingmachines,
    R.drawable.provider_catalog_tinfoil,
    R.drawable.provider_catalog_togetherai,
    R.drawable.provider_catalog_tokengo,
    R.drawable.provider_catalog_tokenrouter,
    R.drawable.provider_catalog_trustedrouter,
    R.drawable.provider_catalog_umans_2d_ai_2d_coding_2d_plan,
    R.drawable.provider_catalog_umans_2d_ai,
    R.drawable.provider_catalog_unorouter,
    R.drawable.provider_catalog_v0,
    R.drawable.provider_catalog_vancine,
    R.drawable.provider_catalog_venice,
    R.drawable.provider_catalog_vercel,
    R.drawable.provider_catalog_vispark,
    R.drawable.provider_catalog_vivgrid,
    R.drawable.provider_catalog_volcengine_2d_coding_2d_plan,
    R.drawable.provider_catalog_volcengine,
    R.drawable.provider_catalog_vultr,
    R.drawable.provider_catalog_wafer_2e_ai,
    R.drawable.provider_catalog_wallaby,
    R.drawable.provider_catalog_wandb,
    R.drawable.provider_catalog_watsonx,
    R.drawable.provider_catalog_xai,
    R.drawable.provider_catalog_xiaomi_2d_token_2d_plan_2d_ams,
    R.drawable.provider_catalog_xiaomi_2d_token_2d_plan_2d_cn,
    R.drawable.provider_catalog_xiaomi_2d_token_2d_plan_2d_sgp,
    R.drawable.provider_catalog_xiaomi,
    R.drawable.provider_catalog_xpersona,
    R.drawable.provider_catalog_zai_2d_coding_2d_plan,
    R.drawable.provider_catalog_zai,
    R.drawable.provider_catalog_zeldoc,
    R.drawable.provider_catalog_zenifra,
    R.drawable.provider_catalog_zhipuai_2d_coding_2d_plan,
    R.drawable.provider_catalog_zhipuai,
    R.drawable.provider_catalog_chutes,
    R.drawable.provider_catalog_cline_2d_pass,
    R.drawable.provider_catalog_gmicloud -> true
    else -> false
}
