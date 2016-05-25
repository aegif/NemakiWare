package jp.aegif.nemaki.util.lock;

public class UniqueObjectId {
	private String repositoryId;
	private String objectId;
	
	public UniqueObjectId(String repositoryId, String objectId) {
		super();
		this.repositoryId = repositoryId;
		this.objectId = objectId;
	}
	
	public String getRepositoryId() {
		return repositoryId;
	}
	public void setRepositoryId(String repositoryId) {
		this.repositoryId = repositoryId;
	}
	public String getObjectId() {
		return objectId;
	}
	public void setObjectId(String objectId) {
		this.objectId = objectId;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((objectId == null) ? 0 : objectId.hashCode());
		result = prime * result + ((repositoryId == null) ? 0 : repositoryId.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		UniqueObjectId other = (UniqueObjectId) obj;
		if (objectId == null) {
			if (other.objectId != null)
				return false;
		} else if (!objectId.equals(other.objectId))
			return false;
		if (repositoryId == null) {
			if (other.repositoryId != null)
				return false;
		} else if (!repositoryId.equals(other.repositoryId))
			return false;
		return true;
	}
	
	
}
